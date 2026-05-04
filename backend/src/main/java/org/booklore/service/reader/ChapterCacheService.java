package org.booklore.service.reader;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.AppProperties;
import org.booklore.exception.ApiError;
import org.booklore.service.ArchiveService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing the on-disk extraction cache for reader chapters.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChapterCacheService {

    private static final long MTIME_TOLERANCE_MS = 2000;

    private final AppProperties appProperties;
    private final ArchiveService archiveService;
    private final ConcurrentHashMap<String, Object> cacheLocks = new ConcurrentHashMap<>();

    /**
     * Ensures all pages of a CBX archive are extracted to the disk cache.
     * Extracts pages in parallel using StructuredTaskScope.
     * Concurrent native libarchive access is now safely handled via synchronized blocks in ArchiveService.
     */
    public void prepareCbxCache(String cacheKey, Path cbxPath, List<String> entries) throws IOException {
        Object lock = cacheLocks.computeIfAbsent(cacheKey, _ -> new Object());
        synchronized (lock) {
            Path cacheDir = getCacheDir(cacheKey);
            if (!Files.exists(cacheDir)) {
                Files.createDirectories(cacheDir);
            }

            // Only extract if the cache is empty or stale
            if (isCacheStale(cacheDir, cbxPath, entries.size())) {
                log.info("Populating disk cache for {}: {} pages", cacheKey, entries.size());

                try (var scope = StructuredTaskScope.open(Joiner.awaitAllSuccessfulOrThrow())) {
                    for (int i = 0; i < entries.size(); i++) {
                        final int index = i;
                        final String entryName = entries.get(i);
                        scope.fork(() -> {
                            Path target = cacheDir.resolve("page_" + (index + 1) + ".jpg");
                            if (!Files.exists(target) || Files.size(target) == 0) {
                                writeAtomically(target, out ->
                                        archiveService.transferEntryTo(cbxPath, entryName, out));
                            }
                            return null;
                        });
                    }
                    scope.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Cache preparation interrupted", e);
                } catch (Exception e) {
                    throw new IOException("Failed to populate cache", e);
                }

                // Mark cache as fresh by setting its mtime to match the archive
                Files.setLastModifiedTime(cacheDir, Files.getLastModifiedTime(cbxPath));
            }
        }
    }

    public Path getCachedPage(String cacheKey, int pageNumber) {
        return getCacheDir(cacheKey).resolve("page_" + pageNumber + ".jpg");
    }

    public boolean hasPage(String cacheKey, int pageNumber) {
        Path pagePath = getCachedPage(cacheKey, pageNumber);
        try {
            return Files.exists(pagePath) && Files.size(pagePath) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Writes data to a temp file in the same directory, then atomically moves
     * it to the target path. If the write fails, the partial temp file is
     * cleaned up and the target is never touched.
     */
    void writeAtomically(Path target, IOConsumer<OutputStream> writer) throws IOException {
        Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString() + ".", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                writer.accept(out);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @FunctionalInterface
    interface IOConsumer<T> {
        void accept(T t) throws IOException;
    }

    private Path getCacheDir(String cacheKey) {
        if (cacheKey == null || cacheKey.contains("..") || cacheKey.contains("/") || cacheKey.contains("\\")) {
            throw ApiError.INVALID_INPUT.createException("Invalid cache key: " + cacheKey);
        }
        return Paths.get(appProperties.getPathConfig(), "cache", "chapters", cacheKey);
    }

    private boolean isCacheStale(Path cacheDir, Path sourcePath, int expectedPages) throws IOException {
        if (!Files.exists(cacheDir)) return true;

        for (int i = 1; i <= expectedPages; i++) {
            Path page = cacheDir.resolve("page_" + i + ".jpg");
            if (!Files.exists(page) || Files.size(page) == 0) return true;
        }

        long cacheMtime = Files.getLastModifiedTime(cacheDir).toMillis();
        long sourceMtime = Files.getLastModifiedTime(sourcePath).toMillis();
        return Math.abs(cacheMtime - sourceMtime) > MTIME_TOLERANCE_MS;
    }
}
