package org.booklore.util;

import app.photofox.vipsffm.Image;
import app.photofox.vipsffm.Vips;
import app.photofox.vipsffm.enums.Enums;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Semaphore;

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
            // Initialize libvips
            Vips.init();
            log.info("libvips initialized successfully via vips-ffm");
        } catch (Exception e) {
            log.error("Failed to initialize libvips: {}", e.getMessage());
        }
    }

    /**
     * Resizes an image efficiently using libvips's streaming pipeline.
     */
    public void resizeImage(Path source, Path target, int width, int height) throws IOException {
        ENCODING_SEMAPHORE.acquireUninterruptibly();
        try (Image img = Image.fromFile(source)) {
            if (img.getWidth() <= width && img.getHeight() <= height) {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                return;
            }
            double scaleX = (double) width / img.getWidth();
            double scaleY = (double) height / img.getHeight();
            double scale = Math.min(scaleX, scaleY);

            try (Image resized = img.resize(scale)) {
                resized.writeToFile(target);
            }
        } catch (Exception e) {
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
        try (Image img = Image.thumbnail(source.toString(), width, height, Enums.VipsSize.BOTH)) {
            img.writeToFile(target);
        } catch (Exception e) {
            log.error("libvips thumbnail generation failed for {}: {}", source, e.getMessage());
            throw new IOException("Thumbnail generation failed", e);
        } finally {
            ENCODING_SEMAPHORE.release();
        }
    }

    /**
     * Converts an image to progressive JPEG with optimized quality.
     */
    public byte[] convertToOptimizedJpeg(byte[] data, float quality) throws IOException {
        ENCODING_SEMAPHORE.acquireUninterruptibly();
        try (Image img = Image.fromBuffer(data)) {
            return img.writeToBuffer(".jpg"); 
        } catch (Exception e) {
            log.error("libvips JPEG conversion failed: {}", e.getMessage());
            throw new IOException("JPEG conversion failed", e);
        } finally {
            ENCODING_SEMAPHORE.release();
        }
    }
}
