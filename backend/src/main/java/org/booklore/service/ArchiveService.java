package org.booklore.service;

import com.github.gotson.nightcompress.Archive;
import com.github.gotson.nightcompress.ArchiveEntry;
import com.github.gotson.nightcompress.LibArchiveException;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.nativelib.NativeLibraries;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Slf4j
@Service
public class ArchiveService {
    private static final int LOCK_STRIPE_COUNT = 256;
    private final ReentrantLock[] lockStripes = IntStream.range(0, LOCK_STRIPE_COUNT)
            .mapToObj(_ -> new ReentrantLock())
            .toArray(ReentrantLock[]::new);

    // Route through the JVM-wide serialized native loader.
    private final boolean available = NativeLibraries.get().isLibArchiveAvailable();

    private ReentrantLock getFileLock(Path path) {
        // Normalize to absolute path for consistent hashing across callers
        int hash = path.toAbsolutePath().normalize().hashCode();
        return lockStripes[Math.floorMod(hash, LOCK_STRIPE_COUNT)];
    }

    private void requireAvailable() throws IOException {
        if (!available) {
            throw new IOException("LibArchive is not available - cannot process archive");
        }
    }

    public static boolean isAvailable() {
        return NativeLibraries.get().isLibArchiveAvailable();
    }

    public record Entry(String name, long size) {}

    private Entry getEntryFromArchiveEntry(ArchiveEntry archiveEntry) {
        return new Entry(archiveEntry.getName(), archiveEntry.getSize());
    }

    public List<Entry> getEntries(Path path) throws IOException {
        if (isZipPath(path)) {
            try (ZipFile zip = new ZipFile(path.toFile())) {
                return zip.stream()
                        .map(ze -> new Entry(ze.getName(), ze.getSize()))
                        .toList();
            }
        }
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
        if (isZipPath(path)) {
            try (ZipFile zip = new ZipFile(path.toFile())) {
                ZipEntry entry = zip.getEntry(entryName);
                if (entry != null) {
                    try (InputStream is = zip.getInputStream(entry)) {
                        return is.transferTo(outputStream);
                    }
                }
            }
            throw new IOException("Entry not found in ZIP: " + entryName);
        }

        requireAvailable();
        // We cannot directly use the NightCompress `InputStream` as it is limited
        // in its implementation and will cause fatal errors.  Instead, we can use
        // the `transferTo` on an output stream to copy data around.
        ReentrantLock lock = getFileLock(path);
        lock.lock();
        try (InputStream inputStream = Archive.getInputStream(path, entryName)) {
            if (inputStream != null) {
                try {
                    return inputStream.transferTo(outputStream);
                } finally {
                    // NightCompress fails with a SIGSEGV if you do not read the
                    // entirety of the input stream from the zip.
                    inputStream.transferTo(OutputStream.nullOutputStream());
                }
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

    /**
     * Reads at most {@code maxBytes} from the given archive entry.
     * This is used to read image headers for dimension detection without
     * loading the full (potentially multi-MB) image into memory.
     *
     * @return a byte array of at most {@code maxBytes} containing the
     *         leading bytes of the entry
     */
    public byte[] getEntryBytesPrefix(Path path, String entryName, int maxBytes) throws IOException {
        if (maxBytes < 0) {
            throw ApiError.INVALID_INPUT.createException("maxBytes must be non-negative");
        }
        var bounded = new BoundedOutputStream(maxBytes);
        try {
            transferEntryTo(path, entryName, bounded);
        } catch (BoundedOutputStream.LimitReachedException _) {
            // expected, we only needed the prefix
        } catch (IOException e) {
            if (!(e.getCause() instanceof BoundedOutputStream.LimitReachedException)) {
                throw e;
            }
            // expected, we only needed the prefix
        }
        return bounded.toByteArray();
    }

    private static final Set<String> ZIP_EXTENSIONS = Set.of("cbz", "zip", "epub");

    private static boolean isZipPath(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && ZIP_EXTENSIONS.contains(name.substring(dot + 1))) {
            return true;
        }

        // Fast magic number check fallback: ZIP/CBZ/EPUB start with "PK\03\04" (0x50 4B 03 04)
        try (InputStream is = Files.newInputStream(path)) {
            return is.read() == 0x50 &&
                   is.read() == 0x4B &&
                   is.read() == 0x03 &&
                   is.read() == 0x04;
        } catch (IOException e) {
            return false;
        }
    }

    static final class BoundedOutputStream extends OutputStream {
        private final byte[] buf;
        private int count;

        BoundedOutputStream(int limit) {
            this.buf = new byte[limit];
        }

        @Override
        public void write(int b) throws IOException {
            if (count >= buf.length) throw new LimitReachedException();
            buf[count++] = (byte) b;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            int remaining = buf.length - count;
            if (remaining <= 0) throw new LimitReachedException();
            int toCopy = Math.min(len, remaining);
            System.arraycopy(b, off, buf, count, toCopy);
            count += toCopy;
            if (toCopy < len) throw new LimitReachedException();
        }

        byte[] toByteArray() {
            return Arrays.copyOf(buf, count);
        }

        static final class LimitReachedException extends IOException {
            LimitReachedException() { super("Bounded output limit reached"); }
        }
    }

    public long extractEntryToPath(Path path, String entryName, Path outputPath) throws IOException {
        requireAvailable();
        ReentrantLock lock = getFileLock(path);
        lock.lock();

        boolean hasCreatedFile = false;
        try (OutputStream outputStream = Files.newOutputStream(outputPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            hasCreatedFile = true;

            return transferEntryTo(path, entryName, outputStream);
        } catch (Exception e) {
            if (hasCreatedFile) {
                try {
                    Files.deleteIfExists(outputPath);
                } catch (Exception ce) {
                    e.addSuppressed(ce);
                }
            }

            throw new IOException("Failed to extract from archive: " + e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }
}
