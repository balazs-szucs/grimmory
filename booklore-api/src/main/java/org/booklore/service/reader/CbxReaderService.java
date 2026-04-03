package org.booklore.service.reader;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.response.CbxPageDimension;
import org.booklore.model.dto.response.CbxPageInfo;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookRepository;
import org.booklore.util.FileUtils;
import org.grimmory.comic4j.archive.ComicArchiveReader;
import org.grimmory.comic4j.image.ImageDimensions;
import org.grimmory.comic4j.image.ImageEntry;
import org.grimmory.comic4j.image.ImageProbe;
import org.springframework.stereotype.Service;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class CbxReaderService {

    private static final int MAX_CACHE_ENTRIES = 50;

    private final BookRepository bookRepository;
    private final Map<String, CachedArchiveMetadata> archiveCache = new ConcurrentHashMap<>();

    private static class CachedArchiveMetadata {
        final List<ImageEntry> imageEntries;
        final long lastModified;
        volatile long lastAccessed;

        CachedArchiveMetadata(List<ImageEntry> imageEntries, long lastModified) {
            this.imageEntries = List.copyOf(imageEntries);
            this.lastModified = lastModified;
            this.lastAccessed = System.currentTimeMillis();
        }
    }

    public List<Integer> getAvailablePages(Long bookId) {
        return getAvailablePages(bookId, null);
    }

    public List<Integer> getAvailablePages(Long bookId, String bookType) {
        Path cbxPath = getBookPath(bookId, bookType);
        try {
            List<ImageEntry> imageEntries = getImageEntriesCached(cbxPath);
            return IntStream.rangeClosed(1, imageEntries.size())
                    .boxed()
                    .collect(Collectors.toList());
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
            List<ImageEntry> imageEntries = getImageEntriesCached(cbxPath);
            List<CbxPageInfo> pageInfoList = new ArrayList<>();
            for (int i = 0; i < imageEntries.size(); i++) {
                pageInfoList.add(CbxPageInfo.builder()
                        .pageNumber(i + 1)
                        .displayName(imageEntries.get(i).displayName())
                        .build());
            }
            return pageInfoList;
        } catch (IOException e) {
            log.error("Failed to read archive for book {}", bookId, e);
            throw ApiError.FILE_READ_ERROR.createException("Failed to read archive: " + e.getMessage());
        }
    }

    public List<CbxPageDimension> getPageDimensions(Long bookId) {
        return getPageDimensions(bookId, null);
    }

    public List<CbxPageDimension> getPageDimensions(Long bookId, String bookType) {
        Path cbxPath = getBookPath(bookId, bookType);
        try {
            List<ImageDimensions> dims = ComicArchiveReader.getPageDimensions(cbxPath);
            List<CbxPageDimension> dimensions = new ArrayList<>();
            for (int i = 0; i < dims.size(); i++) {
                ImageDimensions dim = dims.get(i);
                if (dim != null) {
                    dimensions.add(CbxPageDimension.builder()
                            .pageNumber(i + 1)
                            .width(dim.width())
                            .height(dim.height())
                            .wide(dim.wide())
                            .build());
                } else {
                    dimensions.add(CbxPageDimension.builder()
                            .pageNumber(i + 1)
                            .width(0)
                            .height(0)
                            .wide(false)
                            .build());
                }
            }
            return dimensions;
        } catch (Exception e) {
            log.error("Failed to read page dimensions for book {}", bookId, e);
            throw ApiError.FILE_READ_ERROR.createException("Failed to read page dimensions: " + e.getMessage());
        }
    }

    public void streamPageImage(Long bookId, int page, OutputStream outputStream) throws IOException {
        streamPageImage(bookId, null, page, outputStream);
    }

    public void streamPageImage(Long bookId, String bookType, int page, OutputStream outputStream) throws IOException {
        Path cbxPath = getBookPath(bookId, bookType);
        CachedArchiveMetadata metadata = getCachedMetadata(cbxPath);
        validatePageRequest(bookId, page, metadata.imageEntries);
        String entryName = metadata.imageEntries.get(page - 1).name();
        byte[] imageData = ComicArchiveReader.extractImage(cbxPath, entryName);
        outputStream.write(imageData);
    }

    private Path getBookPath(Long bookId, String bookType) {
        BookEntity bookEntity = bookRepository.findByIdWithBookFiles(bookId).orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));
        if (bookType != null) {
            BookFileType requestedType = BookFileType.valueOf(bookType.toUpperCase());
            BookFileEntity bookFile = bookEntity.getBookFiles().stream()
                    .filter(bf -> bf.getBookType() == requestedType)
                    .findFirst()
                    .orElseThrow(() -> ApiError.FILE_NOT_FOUND.createException("No file of type " + bookType + " found for book"));
            return bookFile.getFullFilePath();
        }
        return FileUtils.getBookFullPath(bookEntity);
    }

    private void validatePageRequest(Long bookId, int page, List<ImageEntry> imageEntries) throws FileNotFoundException {
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
        CachedArchiveMetadata cached = archiveCache.get(cacheKey);
        if (cached != null && cached.lastModified == currentModified) {
            cached.lastAccessed = System.currentTimeMillis();
            log.debug("Cache hit for archive: {}", cbxPath.getFileName());
            return cached;
        }
        log.debug("Cache miss for archive: {}, scanning...", cbxPath.getFileName());
        long lastModified = Files.getLastModifiedTime(cbxPath).toMillis();
        List<ImageEntry> entries = ComicArchiveReader.listImages(cbxPath);
        CachedArchiveMetadata newMetadata = new CachedArchiveMetadata(entries, lastModified);
        archiveCache.put(cacheKey, newMetadata);
        evictOldestCacheEntries();
        return newMetadata;
    }

    private List<ImageEntry> getImageEntriesCached(Path cbxPath) throws IOException {
        return getCachedMetadata(cbxPath).imageEntries;
    }

    private void evictOldestCacheEntries() {
        if (archiveCache.size() <= MAX_CACHE_ENTRIES) {
            return;
        }
        List<String> keysToRemove = archiveCache.entrySet().stream()
                .sorted(Comparator.comparingLong(e -> e.getValue().lastAccessed))
                .limit(archiveCache.size() - MAX_CACHE_ENTRIES)
                .map(Map.Entry::getKey)
                .toList();
        keysToRemove.forEach(key -> {
            archiveCache.remove(key);
            log.debug("Evicted cache entry: {}", key);
        });
    }
}
