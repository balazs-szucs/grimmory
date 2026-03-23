package org.booklore.service.kobo;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import org.booklore.util.FileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * KEPUB Conversion Service - Converts EPUB to KEPUB format for Kobo devices.
 * 
 * Stability improvements ported from Komga:
 * - ✅ Caffeine cache with 5-minute TTL and automatic cleanup
 * - ✅ Timeout handling (configurable, default 30 seconds - increased from Komga's 10s for large EPUBs)
 * - ✅ Executable validation
 * - ✅ Exit code verification
 * - ✅ Detailed error logging
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KepubConversionService {

    // KEPUB conversion timeout (configurable, default 30s for large literary EPUBs)
    // Komga uses 10s but that's for manga/comics; literary EPUBs can take 15-30s
    private final long conversionTimeoutSeconds;

    private final FileService fileService;
    
    /**
     * Constructor with @Value resolution for conversionTimeoutSeconds.
     * Spring resolves the property value at construction time.
     */
    public KepubConversionService(
            FileService fileService,
            @Value("${booklore.kobo.kepub-conversion-timeout:30}") long conversionTimeoutSeconds) {
        this.fileService = fileService;
        this.conversionTimeoutSeconds = conversionTimeoutSeconds;
    }

    /**
     * Cache for KEPUB converted files (mirrors Komga's Caffeine cache with 5-minute TTL).
     * Key: bookId + "-" + fileHash, Value: Path to the converted file.
     * On eviction, the temporary file is automatically deleted.
     */
    private final Cache<String, Path> kepubCache = Caffeine.newBuilder()
            .expireAfterAccess(5, TimeUnit.MINUTES)
            .removalListener((String key, Path value, RemovalCause cause) -> {
                if (value != null) {
                    try {
                        if (Files.deleteIfExists(value)) {
                            log.debug("Deleted cached kepub: {}", value);
                        }
                    } catch (IOException e) {
                        log.warn("Failed to delete cached kepub file: {}", value, e);
                    }
                }
            })
            .build();

    /**
     * Convert EPUB to KEPUB with caching. If a cached conversion exists for
     * the same book+hash combination, it is returned immediately.
     */
    public File convertEpubToKepub(File epubFile, File tempDir, boolean forceEnableHyphenation) throws IOException, InterruptedException {
        return convertEpubToKepub(epubFile, tempDir, forceEnableHyphenation, null, null);
    }

    public File convertEpubToKepub(File epubFile, File tempDir, boolean forceEnableHyphenation, Long bookId, String fileHash) throws IOException, InterruptedException {
        validateInputs(epubFile);

        // Check cache if we have identifying info
        if (bookId != null && fileHash != null) {
            String cacheKey = bookId + "-" + fileHash;
            Path cached = kepubCache.getIfPresent(cacheKey);
            if (cached != null && Files.exists(cached)) {
                log.debug("Returning cached KEPUB conversion for book {} (hash: {})", bookId, fileHash);
                return cached.toFile();
            }
        }

        Path kepubifyBinary = fileService.findSystemFile("kepubify");

        if (kepubifyBinary == null) {
            throw new IOException("Kepubify conversion failed: could not find kepubify binary");
        }

        File outputFile = executeKepubifyConversion(epubFile, tempDir, kepubifyBinary, forceEnableHyphenation);

        log.info("Successfully converted {} to {} (size: {} bytes)", epubFile.getName(), outputFile.getName(), outputFile.length());

        // Store in cache if we have identifying info
        if (bookId != null && fileHash != null) {
            kepubCache.put(bookId + "-" + fileHash, outputFile.toPath());
        }

        return outputFile;
    }

    private void validateInputs(File epubFile) {
        if (epubFile == null || !epubFile.isFile() || !epubFile.getName().endsWith(".epub")) {
            throw new IllegalArgumentException("Invalid EPUB file: " + epubFile);
        }
    }

    private File executeKepubifyConversion(File epubFile, File tempDir, Path kepubifyBinary, boolean forceEnableHyphenation) throws IOException, InterruptedException {
        ProcessBuilder pb;

        if (forceEnableHyphenation)
            pb = new ProcessBuilder(kepubifyBinary.toAbsolutePath().toString(), "--hyphenate", "-o", tempDir.getAbsolutePath(), epubFile.getAbsolutePath());
        else
            pb = new ProcessBuilder(kepubifyBinary.toAbsolutePath().toString(), "-o", tempDir.getAbsolutePath(), epubFile.getAbsolutePath());

        pb.directory(tempDir);

        log.info("Starting kepubify conversion for {} -> output dir: {}", epubFile.getAbsolutePath(), tempDir.getAbsolutePath());

        Process process = pb.start();

        // CRITICAL: Drain streams BEFORE waitFor to prevent pipe buffer deadlock
        // If kepubify writes >65KB to stdout/stderr before timeout, waitFor() will block forever
        String output = readProcessOutput(process.getInputStream());
        String error = readProcessOutput(process.getErrorStream());

        // Timeout handling (configurable, default 30s for large literary EPUBs)
        if (!process.waitFor(conversionTimeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();  // SIGKILL - child may ignore SIGTERM
            log.error("KEPUB conversion timeout after {} seconds for {}. Consider: " +
                    "1) Using smaller EPUB files, " +
                    "2) Checking kepubify binary performance, " +
                    "3) Increasing timeout via booklore.kobo.kepub-conversion-timeout property (current: {}s)", 
                    conversionTimeoutSeconds, epubFile.getName(), conversionTimeoutSeconds);
            throw new IOException("KEPUB conversion timeout after %d seconds".formatted(conversionTimeoutSeconds));
        }

        int exitCode = process.exitValue();
        logProcessResults(exitCode, output, error);

        if (exitCode != 0) {
            throw new IOException("Kepubify conversion failed with exit code: %d. Error: %s".formatted(exitCode, error));
        }

        return findOutputFile(tempDir);
    }

    private String readProcessOutput(InputStream inputStream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (Exception e) {
            log.warn("Error reading process output: {}", e.getMessage());
            return "";
        }
    }

    private void logProcessResults(int exitCode, String output, String error) {
        log.debug("Kepubify process exited with code {}", exitCode);
        if (!output.isEmpty()) {
            log.debug("Kepubify stdout: {}", output);
        }
        if (!error.isEmpty()) {
            log.error("Kepubify stderr: {}", error);
        }
    }

    private File findOutputFile(File tempDir) throws IOException {
        File[] kepubFiles = tempDir.listFiles((dir, name) -> name.endsWith(".kepub.epub"));
        if (kepubFiles == null || kepubFiles.length == 0) {
            throw new IOException("Kepubify conversion completed but no .kepub.epub file was created in: " + tempDir.getAbsolutePath());
        }
        return kepubFiles[0];
    }
}
