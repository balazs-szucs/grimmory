package org.booklore.service.reader;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.grimmory.pdfium4j.PdfDocument;
import org.grimmory.pdfium4j.model.Bookmark;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.response.PdfBookInfo;
import org.booklore.model.dto.response.PdfOutlineItem;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookRepository;
import org.booklore.util.FileUtils;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.booklore.service.FileStreamingService;
import org.springframework.http.MediaType;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.IntStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class PdfReaderService {

    private static final int MAX_CACHE_ENTRIES = 15;
    private static final float DEFAULT_DPI = 200f;
    private static final int PREFETCH_AHEAD = 2;

    private final BookRepository bookRepository;
    private final ChapterCacheService chapterCacheService;
    private final FileStreamingService fileStreamingService;
    private final ExecutorService readerCacheExecutor;
    private final Semaphore readerCpuSemaphore;

    /** Tracks pages whose async pre-render has already been submitted. */
    private final Cache<String, Boolean> prefetchInflight = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(50_000)
            .build();

    /** Lightweight metadata cache - no native handles, just page count + outline. */
    private final Cache<String, CachedPdfMetadata> metadataCache = Caffeine.newBuilder()
            .maximumSize(MAX_CACHE_ENTRIES)
            .expireAfterAccess(Duration.ofMinutes(30))
            .build();

    private record ReaderCacheKey(Long bookId, BookFileType bookType, long lastModified, long size) {}
    private record CachedPdfMetadata(int pageCount, long lastModified, long size, List<PdfOutlineItem> outline) {}

    public void initCache(Long bookId, String bookType) throws IOException {
        Path pdfPath = getBookPath(bookId, bookType);
        CachedPdfMetadata metadata = getCachedMetadata(pdfPath);
        ReaderCacheKey cacheKey = getCacheKey(bookId, bookType, metadata.lastModified, metadata.size);
        String diskKey = getDiskKey(cacheKey);
        ReentrantLock lock = chapterCacheService.lockForCacheKey(diskKey);

        lock.lock();
        try {
            Path cacheDir = chapterCacheService.getCachedPage(diskKey, 1).getParent();
            if (!Files.exists(cacheDir)) {
                Files.createDirectories(cacheDir);
            }

            if (metadata.pageCount > 0
                    && chapterCacheService.hasPage(diskKey, 1)
                    && chapterCacheService.hasPage(diskKey, metadata.pageCount)) {
                return;
            }

            log.info("Populating PDF disk cache for {}: {} pages", diskKey, metadata.pageCount);

            try (PdfDocument doc = PdfDocument.open(pdfPath)) {
                for (int i = 1; i <= metadata.pageCount; i++) {
                    Path target = chapterCacheService.getCachedPage(diskKey, i);
                    if (!Files.exists(target) || Files.size(target) == 0) {
                        byte[] jpeg = renderPage(doc, i - 1);
                        writeAtomically(target, jpeg);
                    }
                }
            }

            Files.setLastModifiedTime(cacheDir, Files.getLastModifiedTime(pdfPath));
        } finally {
            lock.unlock();
        }
    }

    public List<Integer> getAvailablePages(Long bookId) {
        return getAvailablePages(bookId, null);
    }

    public List<Integer> getAvailablePages(Long bookId, String bookType) {
        Path pdfPath = getBookPath(bookId, bookType);
        try {
            CachedPdfMetadata metadata = getCachedMetadata(pdfPath);
            return IntStream.rangeClosed(1, metadata.pageCount)
                    .boxed()
                    .toList();
        } catch (IOException e) {
            log.error("Failed to read PDF for book {}", bookId, e);
            throw ApiError.FILE_READ_ERROR.createException("Failed to read PDF: " + e.getMessage());
        }
    }

    public PdfBookInfo getBookInfo(Long bookId, String bookType) {
        Path pdfPath = getBookPath(bookId, bookType);
        try {
            CachedPdfMetadata metadata = getCachedMetadata(pdfPath);
            return PdfBookInfo.builder()
                    .pageCount(metadata.pageCount)
                    .outline(metadata.outline)
                    .build();
        } catch (IOException e) {
            log.error("Failed to read PDF for book {}", bookId, e);
            throw ApiError.FILE_READ_ERROR.createException("Failed to read PDF: " + e.getMessage());
        }
    }

    public void streamPageImage(Long bookId, int page, OutputStream outputStream) throws IOException {
        streamPageImage(bookId, null, page, outputStream);
    }

    public void streamPageImage(Long bookId, String bookType, int page, OutputStream outputStream) throws IOException {
        Path pdfPath = getBookPath(bookId, bookType);
        CachedPdfMetadata metadata = getCachedMetadata(pdfPath);
        ReaderCacheKey cacheKey = getCacheKey(bookId, bookType, metadata.lastModified, metadata.size);
        String diskKey = getDiskKey(cacheKey);

        validatePageRequest(bookId, page, metadata.pageCount);

        Path cached = renderPageToDiskOnce(pdfPath, diskKey, page);
        Files.copy(cached, outputStream);
    }

    public void streamPageImage(Long bookId, String bookType, int page, HttpServletRequest request, HttpServletResponse response) throws IOException {
        Path pdfPath = getBookPath(bookId, bookType);
        CachedPdfMetadata metadata = getCachedMetadata(pdfPath);
        ReaderCacheKey cacheKey = getCacheKey(bookId, bookType, metadata.lastModified, metadata.size);
        String diskKey = getDiskKey(cacheKey);

        validatePageRequest(bookId, page, metadata.pageCount);

        Path cached = renderPageToDiskOnce(pdfPath, diskKey, page);

        // Trigger sequential prefetching for better UX
        prefetchPages(bookId, bookType, page + 1, page + PREFETCH_AHEAD, metadata, pdfPath);

        fileStreamingService.streamWithRangeSupport(cached, MediaType.IMAGE_JPEG_VALUE, request, response);

    }

    private void prefetchPages(Long bookId, String bookType, int from, int to, CachedPdfMetadata metadata, Path pdfPath) {
        ReaderCacheKey key = getCacheKey(bookId, bookType, metadata.lastModified, metadata.size);
        String diskKey = getDiskKey(key);
        int end = Math.min(to, metadata.pageCount);
        if (from > end) return;

        List<Integer> pages = new ArrayList<>();
        for (int page = from; page <= end; page++) {
            String prefetchKey = diskKey + ":" + page;
            if (prefetchInflight.asMap().putIfAbsent(prefetchKey, Boolean.TRUE) == null) {
                pages.add(page);
            }
        }

        if (pages.isEmpty()) return;

        readerCacheExecutor.submit(() -> {
            try {
                renderPageBatch(pdfPath, diskKey, pages);
            } catch (Exception e) {
                log.debug("PDF prefetch failed for book {}: {}", bookId, e.getMessage());
            }
        });
    }

    private void renderPageBatch(Path pdfPath, String diskKey, List<Integer> pages) throws IOException {
        ReentrantLock lock = chapterCacheService.lockForCacheKey(diskKey);
        lock.lock();
        try {
            List<Integer> toRender = new ArrayList<>();
            for (int page : pages) {
                Path cached = chapterCacheService.getCachedPage(diskKey, page);
                if (!Files.exists(cached) || Files.size(cached) == 0) {
                    toRender.add(page);
                }
            }

            if (toRender.isEmpty()) {
                return;
            }

            try (PdfDocument doc = PdfDocument.open(pdfPath)) {
                for (int page : toRender) {
                    Path cached = chapterCacheService.getCachedPage(diskKey, page);
                    Files.createDirectories(cached.getParent());
                    byte[] jpeg = renderPage(doc, page - 1);
                    writeAtomically(cached, jpeg);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private Path renderPageToDiskOnce(Path pdfPath, String diskKey, int page) throws IOException {
        ReentrantLock lock = chapterCacheService.lockForCacheKey(diskKey);

        lock.lock();
        try {
            Path cached = chapterCacheService.getCachedPage(diskKey, page);
            if (Files.exists(cached) && Files.size(cached) > 0) {
                return cached;
            }

            Files.createDirectories(cached.getParent());

            try (PdfDocument doc = PdfDocument.open(pdfPath)) {
                byte[] jpeg = renderPage(doc, page - 1);
                writeAtomically(cached, jpeg);
            }
            return cached;
        } finally {
            lock.unlock();
        }
    }

    private ReaderCacheKey getCacheKey(Long bookId, String bookType, long lastModified, long size) {
        BookFileType type = null;
        if (bookType != null) {
            type = BookFileType.fromName(bookType)
                    .orElseThrow(() -> ApiError.INVALID_INPUT.createException("Invalid book type: " + bookType));
        }
        return new ReaderCacheKey(bookId, type, lastModified, size);
    }

    private String getDiskKey(ReaderCacheKey key) {
        return key.bookId() + "_" + (key.bookType() != null ? key.bookType().name() : "DEFAULT") + "_" + key.size() + "_" + key.lastModified();
    }

    private Path getBookPath(Long bookId, String bookType) {
        BookEntity bookEntity = bookRepository.findByIdWithBookFiles(bookId)
                .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));
        if (bookType != null) {
            BookFileType requestedType = BookFileType.fromName(bookType)
                    .orElseThrow(() -> ApiError.INVALID_INPUT.createException("Invalid book type: " + bookType));
            BookFileEntity bookFile = bookEntity.getBookFiles().stream()
                    .filter(bf -> bf.getBookType() == requestedType)
                    .findFirst()
                    .orElseThrow(() -> ApiError.FILE_NOT_FOUND.createException("No file of type " + bookType + " found for book"));
            return bookFile.getFullFilePath();
        }
        return FileUtils.getBookFullPath(bookEntity);
    }

    private void validatePageRequest(Long bookId, int page, int pageCount) throws FileNotFoundException {
        if (pageCount == 0) {
            throw new FileNotFoundException("No pages found for book: " + bookId);
        }
        if (page < 1 || page > pageCount) {
            throw new FileNotFoundException("Page " + page + " out of range [1-" + pageCount + "]");
        }
    }

    private CachedPdfMetadata getCachedMetadata(Path pdfPath) throws IOException {
        String cacheKey = pdfPath.toString();
        long currentModified = Files.getLastModifiedTime(pdfPath).toMillis();
        long currentSize = Files.size(pdfPath);
        CachedPdfMetadata cached = metadataCache.getIfPresent(cacheKey);
        if (cached != null && cached.lastModified == currentModified && cached.size == currentSize) {
            return cached;
        }
        CachedPdfMetadata newMetadata = scanPdfMetadata(pdfPath);
        metadataCache.put(cacheKey, newMetadata);
        return newMetadata;
    }

    private byte[] renderPage(PdfDocument doc, int pageIndex) throws IOException {
        boolean acquired = false;
        try {
            readerCpuSemaphore.acquire();
            acquired = true;
            return doc.renderPageToBytes(pageIndex, (int) DEFAULT_DPI, "jpeg");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting to render PDF page", e);
        } finally {
            if (acquired) {
                readerCpuSemaphore.release();
            }
        }
    }

    private CachedPdfMetadata scanPdfMetadata(Path pdfPath) throws IOException {
        if (!Files.isReadable(pdfPath)) {
            throw new FileNotFoundException("PDF file is not readable: " + pdfPath);
        }
        long lastModified = Files.getLastModifiedTime(pdfPath).toMillis();
        long size = Files.size(pdfPath);

        try (PdfDocument doc = PdfDocument.open(pdfPath)) {
            int pageCount = doc.pageCount();
            List<PdfOutlineItem> outline = extractOutline(doc);
            return new CachedPdfMetadata(pageCount, lastModified, size, outline);
        }
    }

    private List<PdfOutlineItem> extractOutline(PdfDocument doc) {
        List<PdfOutlineItem> outline = new ArrayList<>();
        try {
            List<Bookmark> bookmarks = doc.bookmarks();
            for (Bookmark bookmark : bookmarks) {
                PdfOutlineItem outlineItem = convertBookmark(bookmark);
                if (outlineItem != null) {
                    outline.add(outlineItem);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to extract PDF outline: {}", e.getMessage());
        }
        return outline;
    }

    private PdfOutlineItem convertBookmark(Bookmark bookmark) {
        try {
            String title = bookmark.title();
            if (title == null || title.isBlank()) return null;
            Integer pageNumber = (bookmark.pageIndex() >= 0) ? bookmark.pageIndex() + 1 : null;
            List<PdfOutlineItem> children = new ArrayList<>();
            for (Bookmark child : bookmark.children()) {
                PdfOutlineItem childItem = convertBookmark(child);
                if (childItem != null) children.add(childItem);
            }
            return PdfOutlineItem.builder()
                    .title(title.trim())
                    .pageNumber(pageNumber)
                    .children(children.isEmpty() ? null : children)
                    .build();
        } catch (Exception e) {
            return null;
        }
    }

    private void writeAtomically(Path target, byte[] data) throws IOException {
        Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString() + ".", ".tmp");
        try {
            Files.write(tmp, data);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
