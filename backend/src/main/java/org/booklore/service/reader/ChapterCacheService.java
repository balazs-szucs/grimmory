package org.booklore.service.reader;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.AppProperties;
import org.booklore.exception.ApiError;
import org.booklore.service.ArchiveService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

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
    private final ExecutorService readerCacheExecutor;
    private final ConcurrentHashMap<String, ReentrantLock> cacheLocks = new ConcurrentHashMap<>();

    @Scheduled(fixedDelay = 1, initialDelay = 1, timeUnit = TimeUnit.HOURS)
    public void cleanupCache() {
        AppProperties.Cache config = appProperties.getReader().getCache();
        long maxBytes = (long) config.getMaxSizeGb() * 1024 * 1024 * 1024;
        long maxAgeMillis = Duration.ofDays(config.getMaxAgeDays()).toMillis();
        Instant cutoff = Instant.now().minusMillis(maxAgeMillis);

        Path cacheRoot = Paths.get(appProperties.getPathConfig(), "cache", "chapters");
        if (!Files.exists(cacheRoot)) return;

        try {
            // 1. Delete stale/expired caches
            try (Stream<Path> books = Files.list(cacheRoot)) {
                books.forEach(bookDir -> {
                    try {
                        if (Files.getLastModifiedTime(bookDir).toInstant().isBefore(cutoff)) {
                            log.info("Deleting expired reader cache: {}", bookDir.getFileName());
                            deleteDirectoryRecursively(bookDir);
                        }
                    } catch (IOException e) {
                        log.warn("Failed to check expiry for cache {}: {}", bookDir, e.getMessage());
                    }
                });
            }

            // 2. Bounded size cleanup (LRU-ish based on mtime)
            long currentSize = calculateDirectorySize(cacheRoot);
            if (currentSize > maxBytes) {
                log.info("Reader cache exceeds {} GB (current: {} GB), cleaning up...",
                        config.getMaxSizeGb(), String.format("%.2f", currentSize / 1024.0 / 1024.0 / 1024.0));

                try (Stream<Path> books = Files.list(cacheRoot)) {
                    List<Path> sortedBooks = books
                            .sorted(Comparator.comparingLong(p -> {
                                try {
                                    return Files.getLastModifiedTime(p).toMillis();
                                } catch (IOException e) {
                                    return Long.MAX_VALUE;
                                }
                            }))
                            .toList();

                    for (Path bookDir : sortedBooks) {
                        String key = bookDir.getFileName().toString();
                        ReentrantLock lock = cacheLocks.get(key);
                        if (lock != null && lock.isLocked()) continue;

                        long dirSize = calculateDirectorySize(bookDir);
                        deleteDirectoryRecursively(bookDir);
                        currentSize -= dirSize;
                        if (currentSize <= maxBytes * 0.8) break; // Clean down to 80%
                    }
                }
            }
        } catch (IOException e) {
            log.error("Failed to cleanup reader cache", e);
        }
    }

    private void touch(Path dir) {
        readerCacheExecutor.execute(() -> {
            try {
                Files.setLastModifiedTime(dir, FileTime.from(Instant.now()));
            } catch (IOException ignored) {}
        });
    }

    private long calculateDirectorySize(Path path) throws IOException {
        try (Stream<Path> walk = Files.walk(path)) {
            return walk.filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .sum();
        }
    }

    private void deleteDirectoryRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            log.warn("Failed to delete cache file {}: {}", p, e.getMessage());
                        }
                    });
        }
    }

    /**
     * Ensures all pages of a CBX archive are extracted to the disk cache.
     * Extracts pages sequentially to avoid concurrent native libarchive access
     * which can cause SIGSEGV / out-of-memory crashes in the native heap.
     */
    public void prepareCbxCache(String cacheKey, Path cbxPath, List<String> entries) throws IOException {
        ReentrantLock lock = cacheLocks.computeIfAbsent(cacheKey, _ -> new ReentrantLock());
        lock.lock();
        try {
            Path cacheDir = getCacheDir(cacheKey);
            if (!Files.exists(cacheDir)) {
                Files.createDirectories(cacheDir);
            }

            // Only extract if the cache is empty or stale
            if (isCacheStale(cacheDir, cbxPath, entries.size())) {
                log.info("Populating disk cache for {}: {} pages", cacheKey, entries.size());

                for (int i = 0; i < entries.size(); i++) {
                    Path target = cacheDir.resolve("page_" + (i + 1) + ".jpg");
                    if (!Files.exists(target) || Files.size(target) == 0) {
                        String entryName = entries.get(i);
                        writeAtomically(target, out ->
                                archiveService.transferEntryTo(cbxPath, entryName, out));
                    }
                }

                // Mark cache as fresh by setting its mtime to match the archive
                Files.setLastModifiedTime(cacheDir, Files.getLastModifiedTime(cbxPath));
            }
        } finally {
            lock.unlock();
        }
    }

    public Path getCachedPage(String cacheKey, int pageNumber) {
        Path dir = getCacheDir(cacheKey);
        touch(dir);
        return dir.resolve("page_" + pageNumber + ".jpg");
    }

    public Path getCachedAsset(String cacheKey, String fileName) {
        Path dir = getCacheDir(cacheKey);
        touch(dir);
        return dir.resolve(fileName);
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
        if (cacheKey == null || cacheKey.contains("..")) {
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
