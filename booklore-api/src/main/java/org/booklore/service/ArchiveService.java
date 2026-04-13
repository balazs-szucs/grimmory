package org.booklore.service;

import com.github.gotson.nightcompress.Archive;
import com.github.gotson.nightcompress.ArchiveEntry;
import com.github.gotson.nightcompress.LibArchiveException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

@Slf4j
@Service
public class ArchiveService {
    private final ConcurrentHashMap<String, ReentrantLock> fileLocks = new ConcurrentHashMap<>();
    private volatile boolean available;

    private ReentrantLock getFileLock(Path path) {
        String key = path.toAbsolutePath().normalize().toString();
        return fileLocks.computeIfAbsent(key, k -> new ReentrantLock());
    }

    @PostConstruct
    public void initLibArchive() {
        try {
            // Eagerly load the native library on the Spring startup thread.
            // Loading native libraries is not a thread-safe operation, so this
            // must happen before any concurrent HTTP request can access it.
            available = Archive.isAvailable();
            if (available) {
                log.info("LibArchive loaded successfully");
            } else {
                log.error("LibArchive is not available – CBX/archive features will not work");
            }
        } catch (Throwable e) {
            log.error("LibArchive could not be loaded", e);
            available = false;
        }
    }

    private void requireAvailable() throws IOException {
        if (!available) {
            throw new IOException("LibArchive is not available – cannot process archive");
        }
    }

    public static boolean isAvailable() {
        return Archive.isAvailable();
    }

    public record Entry(String name, long size) {}

    private Entry getEntryFromArchiveEntry(ArchiveEntry archiveEntry) {
        return new Entry(archiveEntry.getName(), archiveEntry.getSize());
    }

    public List<Entry> getEntries(Path path) throws IOException {
        return streamEntries(path).toList();
    }

    public Stream<Entry> streamEntries(Path path) throws IOException {
        requireAvailable();
        ReentrantLock lock = getFileLock(path);
        lock.lock();
        try {
            List<ArchiveEntry> entries = Archive.getEntries(path);
            return entries.stream().map(this::getEntryFromArchiveEntry);
        } catch (LibArchiveException e) {
            throw new IOException("Failed to read archive", e);
        } finally {
            lock.unlock();
        }
    }

    public List<String> getEntryNames(Path path) throws IOException {
        return streamEntryNames(path).toList();
    }

    public Stream<String> streamEntryNames(Path path) throws IOException {
        requireAvailable();
        ReentrantLock lock = getFileLock(path);
        lock.lock();
        try {
            List<ArchiveEntry> entries = Archive.getEntries(path);
            return entries.stream().map(ArchiveEntry::getName);
        } catch (LibArchiveException e) {
            throw new IOException("Failed to read archive", e);
        } finally {
            lock.unlock();
        }
    }

    public long transferEntryTo(Path path, String entryName, OutputStream outputStream) throws IOException {
        requireAvailable();
        // We cannot directly use the NightCompress `InputStream` as it is limited
        // in its implementation and will cause fatal errors.  Instead, we can use
        // the `transferTo` on an output stream to copy data around.
        ReentrantLock lock = getFileLock(path);
        lock.lock();
        try (InputStream inputStream = Archive.getInputStream(path, entryName)) {
            if (inputStream != null) {
                return inputStream.transferTo(outputStream);
            }
        } catch (Exception e) {
            throw new IOException("Failed to extract from archive: " + e.getMessage(), e);
        } finally {
            lock.unlock();
        }

        throw new IOException("Entry not found in archive");
    }

    public byte[] getEntryBytes(Path path, String entryName) throws IOException {
        try (
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ) {
            transferEntryTo(path, entryName, outputStream);

            return outputStream.toByteArray();
        }
    }

    public long extractEntryToPath(Path path, String entryName, Path outputPath) throws IOException {
        requireAvailable();
        ReentrantLock lock = getFileLock(path);
        lock.lock();
        try (InputStream inputStream = Archive.getInputStream(path, entryName)) {
            if (inputStream != null) {
                return Files.copy(inputStream, outputPath);
            }
        } catch (Exception e) {
            throw new IOException("Failed to extract from archive: " + e.getMessage(), e);
        } finally {
            lock.unlock();
        }

        throw new IOException("Entry not found in archive");
    }
}
