package org.booklore.service.reader;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.response.CbxPageDimension;
import org.booklore.model.dto.response.CbxPageInfo;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookRepository;
import org.booklore.service.ArchiveService;
import org.booklore.util.ArchiveUtils;
import org.booklore.util.FileUtils;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.booklore.service.FileStreamingService;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Slf4j
@Service
@RequiredArgsConstructor
public class CbxReaderService {

    private static final String[] SUPPORTED_IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".avif", ".heic", ".gif", ".bmp"};
    private static final int MAX_CACHE_ENTRIES = 50;
    private static final Set<String> SYSTEM_FILES = Set.of(".ds_store", "thumbs.db", "desktop.ini");
    /** Bytes to read from a non-ZIP archive entry for image-header dimension detection. */
    private static final int DIMENSION_PREFIX_BYTES = 64 * 1024;

    private final BookRepository bookRepository;
    private final Cache<String, CachedArchiveMetadata> archiveCache = Caffeine.newBuilder()
            .maximumSize(MAX_CACHE_ENTRIES)
            .expireAfterAccess(Duration.ofMinutes(30))
            .build();

    private final ArchiveService archiveService;
    private final ChapterCacheService chapterCacheService;
    private final FileStreamingService fileStreamingService;
    private final ExecutorService readerCacheExecutor;

    /** Tracks books whose async cache init has already been submitted. */
    private final Cache<String, Boolean> cacheInitSubmitted = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(10_000)
            .build();

    /**
     * Per-book render locks to prevent duplicate renders and memory spikes.
     */
    private static final int LOCK_STRIPES = 64;
    private final ReentrantLock[] renderLocks = IntStream.range(0, LOCK_STRIPES)
            .mapToObj(_ -> new ReentrantLock())
            .toArray(ReentrantLock[]::new);


    private record ReaderCacheKey(Long bookId, BookFileType bookType, long lastModified) {}
    private record CachedArchiveMetadata(List<String> imageEntries, List<CbxPageDimension> pageDimensions, long lastModified) {
        CachedArchiveMetadata {
            imageEntries = List.copyOf(imageEntries);
            pageDimensions = pageDimensions != null ? List.copyOf(pageDimensions) : null;
        }
    }

    public void initCache(Long bookId, String bookType) throws IOException {
        Path cbxPath = getBookPath(bookId, bookType);
        CachedArchiveMetadata metadata = getCachedMetadata(cbxPath);
        ReaderCacheKey cacheKey = getCacheKey(bookId, bookType, metadata.lastModified());
        String diskKey = getDiskKey(cacheKey);
        chapterCacheService.prepareCbxCache(diskKey, cbxPath, metadata.imageEntries());

        if (metadata.pageDimensions() == null) {
            List<CbxPageDimension> dimensions = computeDimensionsFromDiskCache(diskKey, metadata.imageEntries().size());
            CachedArchiveMetadata updated = new CachedArchiveMetadata(metadata.imageEntries(), dimensions, metadata.lastModified());
            archiveCache.put(cbxPath.toString(), updated);
        }
    }

    /**
     * Submits a background task to extract all pages to the disk cache.
     * This runs once per book/type combination and makes subsequent
     * {@link #streamPageImage} calls hit Tier 2 (disk) instead of Tier 3
     * (native extraction per request).
     */
    private void submitBackgroundCacheInit(Long bookId, String bookType, long lastModified) {
        String key = bookId + ":" + bookType + ":" + lastModified;
        if (cacheInitSubmitted.asMap().putIfAbsent(key, Boolean.TRUE) == null) {
            readerCacheExecutor.submit(() -> {
                try {
                    initCache(bookId, bookType);
                } catch (Exception e) {
                    log.warn("Background cache init failed for book {}: {}", bookId, e.getMessage());
                    // Intentionally NOT removing key - failed books should not be retried indefinitely.
                }
            });
        }
    }

    private List<CbxPageDimension> computeDimensionsFromDiskCache(String cacheKey, int pageCount) {
        List<CbxPageDimension> dimensions = new ArrayList<>();
        for (int i = 1; i <= pageCount; i++) {
            Path cachedPage = chapterCacheService.getCachedPage(cacheKey, i);
            try (ImageInputStream iis = ImageIO.createImageInputStream(cachedPage.toFile())) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
                if (readers.hasNext()) {
                    ImageReader reader = readers.next();
                    try {
                        reader.setInput(iis, true, true);
                        int width = reader.getWidth(0);
                        int height = reader.getHeight(0);
                        dimensions.add(CbxPageDimension.builder().pageNumber(i).width(width).height(height).wide(width > height).build());
                        continue;
                    } finally {
                        reader.dispose();
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to read dimensions for cached page {}: {}", i, e.getMessage());
            }
            dimensions.add(CbxPageDimension.builder().pageNumber(i).width(0).height(0).wide(false).build());
        }
        return dimensions;
    }

    public List<Integer> getAvailablePages(Long bookId) {
        return getAvailablePages(bookId, null);
    }

    public List<Integer> getAvailablePages(Long bookId, String bookType) {
        Path cbxPath = getBookPath(bookId, bookType);
        try {
            CachedArchiveMetadata metadata = getCachedMetadata(cbxPath);
            List<String> imageEntries = metadata.imageEntries();
            // Trigger background disk-cache population for faster subsequent page serving
            submitBackgroundCacheInit(bookId, bookType, metadata.lastModified());
            return IntStream.rangeClosed(1, imageEntries.size())
                    .boxed()
                    .toList();
        } catch (IOException e) {
            log.error("Failed to read archive for book {}", bookId, e);
            throw ApiError.FILE_READ_ERROR.createException("Failed to read archive: " + e.getMessage());
        }
    }

    public List<CbxPageInfo> getPageInfo(Long bookId) {
        return getPageInfo(bookId, null);
    }

    public List<CbxPageInfo> getPageInfo(Long bookId, String bookType) {
        Path cbxPath = getBookPath(bookId, bookType);
        try {
            List<String> imageEntries = getImageEntriesFromArchiveCached(cbxPath);
            List<CbxPageInfo> pageInfoList = new ArrayList<>();
            for (int i = 0; i < imageEntries.size(); i++) {
                String entryPath = imageEntries.get(i);
                String displayName = extractDisplayName(entryPath);
                pageInfoList.add(CbxPageInfo.builder()
                        .pageNumber(i + 1)
                        .displayName(displayName)
                        .build());
            }
            return pageInfoList;
        } catch (IOException e) {
            log.error("Failed to read archive for book {}", bookId, e);
            throw ApiError.FILE_READ_ERROR.createException("Failed to read archive: " + e.getMessage());
        }
    }

    public List<CbxPageDimension> getPageDimensions(Long bookId, String bookType) {
        Path cbxPath = getBookPath(bookId, bookType);
        try {
            CachedArchiveMetadata metadata = getCachedMetadata(cbxPath);
            if (metadata.pageDimensions() != null) {
                return metadata.pageDimensions();
            }

            // Try disk cache first (fast, memory-safe)
            ReaderCacheKey ck = getCacheKey(bookId, bookType, metadata.lastModified());
            String diskCacheKey = getDiskKey(ck);
            if (chapterCacheService.hasPage(diskCacheKey, 1) && chapterCacheService.hasPage(diskCacheKey, metadata.imageEntries().size())) {
                List<CbxPageDimension> dimensions = computeDimensionsFromDiskCache(diskCacheKey, metadata.imageEntries().size());
                CachedArchiveMetadata updated = new CachedArchiveMetadata(metadata.imageEntries(), dimensions, metadata.lastModified());
                archiveCache.put(cbxPath.toString(), updated);
                return dimensions;
            }

            // Streaming: read only image headers, not full images
            List<CbxPageDimension> dimensions = readDimensionsStreaming(cbxPath, metadata.imageEntries());

            CachedArchiveMetadata updatedMetadata = new CachedArchiveMetadata(metadata.imageEntries(), dimensions, metadata.lastModified());
            archiveCache.put(cbxPath.toString(), updatedMetadata);

            return dimensions;
        } catch (IOException e) {
            log.error("Failed to read page dimensions for book {}", bookId, e);
            throw ApiError.FILE_READ_ERROR.createException("Failed to read page dimensions: " + e.getMessage());
        }
    }

    /**
     * Reads image dimensions for all pages using only image headers (a few KB
     * per page) instead of loading the full image.
     * <p>
     * For ZIP/CBZ archives the fast path uses {@link java.util.zip.ZipFile}
     * which supports random access, so each entry stream feeds directly into
     * {@link ImageIO}. For non-ZIP archives (RAR, 7z) the first
     * {@value #DIMENSION_PREFIX_BYTES} bytes of each entry are extracted via
     * {@link ArchiveService#getEntryBytesPrefix} which is sufficient for all
     * common image header formats (JPEG SOF, PNG IHDR, WebP VP8, etc.).
     */
    private List<CbxPageDimension> readDimensionsStreaming(Path cbxPath, List<String> imageEntries) {
        // Try the ZipFile fast-path first (random access, no full extraction)
        if (isZipPath(cbxPath)) {
            try {
                return readDimensionsViaZipFile(cbxPath, imageEntries);
            } catch (IOException e) {
                log.debug("ZipFile dimension read failed for {}, falling back to bounded extraction: {}", cbxPath.getFileName(), e.getMessage());
            }
        }

        // Fallback for non-ZIP or on ZipFile failure: bounded prefix extraction
        return readDimensionsViaBoundedPrefix(cbxPath, imageEntries);
    }

    /**
     * Uses a ZipFile handle to stream each entry's image header bytes directly
     * into ImageIO for dimension detection.  Memory cost: only the bytes
     * ImageIO needs to decode the header (typically < 4 KB per page).
     */
    private List<CbxPageDimension> readDimensionsViaZipFile(Path cbxPath, List<String> imageEntries) throws IOException {
        List<CbxPageDimension> dimensions = new ArrayList<>(imageEntries.size());
        try (ZipFile zip = new ZipFile(cbxPath.toFile())) {
            for (int i = 0; i < imageEntries.size(); i++) {
                int pageNumber = i + 1;
                String entryName = imageEntries.get(i);
                ZipEntry entry = zip.getEntry(entryName);
                if (entry != null) {
                    try (InputStream is = zip.getInputStream(entry);
                         ImageInputStream iis = ImageIO.createImageInputStream(is)) {
                        dimensions.add(readDimensionFromImageStream(iis, pageNumber));
                        continue;
                    } catch (Exception e) {
                        log.warn("Failed to read dimensions for page {} via ZipFile (entry: {}): {}", pageNumber, entryName, e.getMessage());
                    }
                }
                dimensions.add(fallbackDimension(pageNumber));
            }
        }
        return dimensions;
    }

    /**
     * For non-ZIP archives (RAR, 7z): extracts only the first
     * {@value #DIMENSION_PREFIX_BYTES} bytes of each entry, enough for image
     * header parsing,  and reads dimensions from that bounded buffer.
     */
    private List<CbxPageDimension> readDimensionsViaBoundedPrefix(Path cbxPath, List<String> imageEntries) {
        List<CbxPageDimension> dimensions = new ArrayList<>(imageEntries.size());
        for (int i = 0; i < imageEntries.size(); i++) {
            int pageNumber = i + 1;
            String entryName = imageEntries.get(i);
            try {
                byte[] prefix = archiveService.getEntryBytesPrefix(cbxPath, entryName, DIMENSION_PREFIX_BYTES);
                try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(prefix))) {
                    dimensions.add(readDimensionFromImageStream(iis, pageNumber));
                    continue;
                }
            } catch (Exception e) {
                log.warn("Failed to read dimensions for page {} via bounded prefix (entry: {}): {}", pageNumber, entryName, e.getMessage());
            }
            dimensions.add(fallbackDimension(pageNumber));
        }
        return dimensions;
    }

    private CbxPageDimension readDimensionFromImageStream(ImageInputStream iis, int pageNumber) throws IOException {
        Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
        if (readers.hasNext()) {
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                return CbxPageDimension.builder()
                        .pageNumber(pageNumber)
                        .width(width)
                        .height(height)
                        .wide(width > height)
                        .build();
            } finally {
                reader.dispose();
            }
        }
        return fallbackDimension(pageNumber);
    }

    private static CbxPageDimension fallbackDimension(int pageNumber) {
        return CbxPageDimension.builder()
                .pageNumber(pageNumber)
                .width(0)
                .height(0)
                .wide(false)
                .build();
    }

    private static boolean isZipPath(Path path) {
        return ArchiveUtils.detectArchiveType(path) == ArchiveUtils.ArchiveType.ZIP;
    }

    private String extractDisplayName(String entryPath) {
        String fileName = baseName(entryPath);
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex > 0) {
            return fileName.substring(0, lastDotIndex);
        }
        return fileName;
    }

    public void streamPageImage(Long bookId, int page, OutputStream outputStream) throws IOException {
        streamPageImage(bookId, null, page, outputStream);
    }

    public void streamPageImage(Long bookId, String bookType, int page, OutputStream outputStream) throws IOException {
        Path cbxPath = getBookPath(bookId, bookType);
        CachedArchiveMetadata metadata = getCachedMetadata(cbxPath);
        validatePageRequest(bookId, page, metadata.imageEntries());

        String entryName = metadata.imageEntries().get(page - 1);
        ReaderCacheKey cacheKey = getCacheKey(bookId, bookType, metadata.lastModified());
        String diskKey = getDiskKey(cacheKey);

        Path cached = getOrExtractPageToCache(cbxPath, cacheKey, diskKey, page, entryName);
        Files.copy(cached, outputStream);
    }

    public void streamPageImage(Long bookId, String bookType, int page, HttpServletRequest request, HttpServletResponse response) throws IOException {
        Path cbxPath = getBookPath(bookId, bookType);
        CachedArchiveMetadata metadata = getCachedMetadata(cbxPath);
        validatePageRequest(bookId, page, metadata.imageEntries());

        String entryName = metadata.imageEntries().get(page - 1);
        String contentType = MediaTypeFactory.getMediaType(entryName)
                .map(MediaType::toString)
                .orElse(MediaType.IMAGE_JPEG_VALUE);

        ReaderCacheKey cacheKey = getCacheKey(bookId, bookType, metadata.lastModified());
        String diskKey = getDiskKey(cacheKey);

        Path cached = getOrExtractPageToCache(cbxPath, cacheKey, diskKey, page, entryName);
        fileStreamingService.streamWithRangeSupport(cached, contentType, request, response);
    }

    private Path getOrExtractPageToCache(
            Path cbxPath,
            ReaderCacheKey cacheKey,
            String diskKey,
            int page,
            String entryName
    ) throws IOException {
        Path cached = chapterCacheService.getCachedPage(diskKey, page);

        if (Files.exists(cached) && Files.size(cached) > 0) {
            return cached;
        }

        ReentrantLock lock = renderLocks[Math.floorMod(cacheKey.hashCode(), LOCK_STRIPES)];
        lock.lock();
        try {
            if (Files.exists(cached) && Files.size(cached) > 0) {
                return cached;
            }

            Files.createDirectories(cached.getParent());
            extractEntryAtomically(cbxPath, entryName, cached);
            return cached;
        } finally {
            lock.unlock();
        }
    }

    private void extractEntryAtomically(Path archive, String entryName, Path target) throws IOException {
        Path tmp = Files.createTempFile(target.getParent(), "cbx-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                archiveService.transferEntryTo(archive, entryName, out);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }


    private ReaderCacheKey getCacheKey(Long bookId, String bookType, long lastModified) {
        BookFileType type = null;
        if (bookType != null) {
            type = BookFileType.fromName(bookType)
                    .orElseThrow(() -> ApiError.INVALID_INPUT.createException("Invalid book type: " + bookType));
        }
        return new ReaderCacheKey(bookId, type, lastModified);
    }

    private String getDiskKey(ReaderCacheKey key) {
        return key.bookId() + "_" + (key.bookType() != null ? key.bookType().name() : "DEFAULT") + "_" + key.lastModified();
    }

    private Path getBookPath(Long bookId, String bookType) {
        BookEntity bookEntity = bookRepository.findByIdForStreaming(bookId).orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));
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

    private void validatePageRequest(Long bookId, int page, List<String> imageEntries) throws FileNotFoundException {
        if (imageEntries.isEmpty()) {
            throw new FileNotFoundException("No image files found for book: " + bookId);
        }
        if (page < 1 || page > imageEntries.size()) {
            throw new FileNotFoundException("Page " + page + " out of range [1-" + imageEntries.size() + "]");
        }
    }

    private CachedArchiveMetadata getCachedMetadata(Path cbxPath) throws IOException {
        String cacheKey = cbxPath.toString();
        long currentModified = Files.getLastModifiedTime(cbxPath).toMillis();
        CachedArchiveMetadata cached = archiveCache.getIfPresent(cacheKey);
        if (cached != null && cached.lastModified() == currentModified) {
            log.debug("Cache hit for archive: {}", cbxPath.getFileName());
            return cached;
        }
        log.debug("Cache miss for archive: {}, scanning...", cbxPath.getFileName());
        CachedArchiveMetadata newMetadata = scanArchiveMetadata(cbxPath);
        archiveCache.put(cacheKey, newMetadata);
        return newMetadata;
    }

    private List<String> getImageEntriesFromArchiveCached(Path cbxPath) throws IOException {
        return getCachedMetadata(cbxPath).imageEntries();
    }

    private CachedArchiveMetadata scanArchiveMetadata(Path cbxPath) throws IOException {
        long lastModified = Files.getLastModifiedTime(cbxPath).toMillis();

        List<String> entries = getImageEntries(cbxPath);
        return new CachedArchiveMetadata(entries, null, lastModified);
    }

    private List<String> getImageEntries(Path cbxPath) throws IOException {
        try {
            return archiveService.streamEntryNames(cbxPath)
                    .filter(this::isImageFile)
                    .sorted(CbxReaderService::sortNaturally)
                    .toList();

        } catch (Exception e) {
            throw new IOException("Failed to read archive: " + e.getMessage(), e);
        }
    }

    private boolean isImageFile(String name) {
        if (!isContentEntry(name)) {
            return false;
        }
        String lower = name.toLowerCase().replace('\\', '/');
        for (String extension : SUPPORTED_IMAGE_EXTENSIONS) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    private boolean isContentEntry(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("__MACOSX/") || normalized.contains("/__MACOSX/")) {
            return false;
        }
        // Prevent path traversal: reject any entry whose path contains ".." as a component.
        // Checks split-by-/ to catch "foo/..", ".." alone, and not just "../" (with trailing slash).
        for (String component : normalized.split("/", -1)) {
            if ("..".equals(component)) {
                return false;
            }
        }
        String baseName = baseName(normalized).toLowerCase();
        if (baseName.startsWith("._") || !baseName.isEmpty() && baseName.charAt(0) == '.') {
            return false;
        }
        return !SYSTEM_FILES.contains(baseName);
    }

    private String baseName(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static int sortNaturally(String s1, String s2) {
        int n1 = s1.length(), n2 = s2.length();
        int i1 = 0, i2 = 0;
        while (i1 < n1 && i2 < n2) {
            char c1 = s1.charAt(i1);
            char c2 = s2.charAt(i2);
            if (Character.isDigit(c1) && Character.isDigit(c2)) {
                long val1 = 0, val2 = 0;
                while (i1 < n1 && Character.isDigit(s1.charAt(i1)) && val1 < Long.MAX_VALUE / 10) {
                    val1 = val1 * 10 + (s1.charAt(i1) - '0');
                    i1++;
                }
                // Skip remaining digits on overflow
                while (i1 < n1 && Character.isDigit(s1.charAt(i1))) i1++;
                while (i2 < n2 && Character.isDigit(s2.charAt(i2)) && val2 < Long.MAX_VALUE / 10) {
                    val2 = val2 * 10 + (s2.charAt(i2) - '0');
                    i2++;
                }
                while (i2 < n2 && Character.isDigit(s2.charAt(i2))) i2++;
                if (val1 != val2) return Long.compare(val1, val2);
            } else {
                int cmp = Character.compare(Character.toLowerCase(c1), Character.toLowerCase(c2));
                if (cmp != 0) return cmp;
                i1++; i2++;
            }
        }
        int lenCmp = Integer.compare(n1, n2);
        return lenCmp != 0 ? lenCmp : s1.compareToIgnoreCase(s2);
    }
}
