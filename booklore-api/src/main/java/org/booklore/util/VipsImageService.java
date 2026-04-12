package org.booklore.util;

import app.photofox.vipsffm.VBlob;
import app.photofox.vipsffm.VImage;
import app.photofox.vipsffm.Vips;
import app.photofox.vipsffm.VipsError;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

/**
 * High-performance image processing service using libvips via vips-ffm.
 * Leverages the Foreign Function & Memory (FFM) API for zero-copy native performance.
 * Uses a semaphore to limit concurrent encoding operations and prevent OOM under load.
 */
@Slf4j
@Service
public class VipsImageService {

    private static final Semaphore ENCODING_SEMAPHORE = new Semaphore(Runtime.getRuntime().availableProcessors());

    static {
        try {
            Vips.init();
            log.info("libvips initialized successfully via vips-ffm");
        } catch (Throwable t) {
            log.error("Failed to initialize libvips: {}", t.getMessage());
        }
    }

    /**
     * Resizes an image efficiently using libvips's streaming pipeline.
     */
    public void resizeImage(Path source, Path target, int width, int height) throws IOException {
        ENCODING_SEMAPHORE.acquireUninterruptibly();
        try {
            Vips.run(arena -> {
                VImage img = VImage.newFromFile(arena, source.toString());
                if (img.getWidth() <= width && img.getHeight() <= height) {
                    try {
                        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        throw new VipsError("File copy failed: " + e.getMessage());
                    }
                    return;
                }
                double scaleX = (double) width / img.getWidth();
                double scaleY = (double) height / img.getHeight();
                double scale = Math.min(scaleX, scaleY);
                VImage resized = img.resize(scale);
                resized.writeToFile(target.toString());
            });
        } catch (VipsError e) {
            log.error("libvips resize failed for {}: {}", source, e.getMessage());
            throw new IOException("Image resize failed", e);
        } finally {
            ENCODING_SEMAPHORE.release();
        }
    }

    /**
     * Generates a thumbnail with smart-cropping (center-weighted).
     */
    public void generateThumbnail(Path source, Path target, int width, int height) throws IOException {
        ENCODING_SEMAPHORE.acquireUninterruptibly();
        try {
            Vips.run(arena -> {
                VImage img = VImage.thumbnail(arena, source.toString(), width);
                img.writeToFile(target.toString());
            });
        } catch (VipsError e) {
            log.error("libvips thumbnail generation failed for {}: {}", source, e.getMessage());
            throw new IOException("Thumbnail generation failed", e);
        } finally {
            ENCODING_SEMAPHORE.release();
        }
    }

    /**
     * Converts an image to JPEG with optimized quality.
     */
    public byte[] convertToOptimizedJpeg(byte[] data, float quality) throws IOException {
        ENCODING_SEMAPHORE.acquireUninterruptibly();
        AtomicReference<byte[]> result = new AtomicReference<>();
        try {
            Vips.run(arena -> {
                VImage img = VImage.newFromBytes(arena, data);
                VBlob blob = img.jpegsaveBuffer();
                result.set(blob.asClonedByteBuffer().array());
            });
            return result.get();
        } catch (VipsError e) {
            log.error("libvips JPEG conversion failed: {}", e.getMessage());
            throw new IOException("JPEG conversion failed", e);
        } finally {
            ENCODING_SEMAPHORE.release();
        }
    }
}
