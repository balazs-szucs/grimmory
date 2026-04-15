package org.booklore.util;

import app.photofox.vipsffm.VBlob;
import app.photofox.vipsffm.VImage;
import app.photofox.vipsffm.Vips;
import app.photofox.vipsffm.VipsError;
import app.photofox.vipsffm.VipsOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

/**
 * High-performance image processing service using libvips via vips-ffm.
 * Leverages the Foreign Function &amp; Memory (FFM) API for zero-copy native performance.
 * Uses a semaphore to limit concurrent encoding operations and prevent OOM under load.
 *
 * <p>vips-ffm auto-initialises libvips on first use of {@link Vips#run}; no manual
 * {@code Vips.init()} call is needed (see vips-ffm README, "Initialisation" section).</p>
 */
@Slf4j
@Service
public class VipsImageService {

    private static final Semaphore ENCODING_SEMAPHORE = new Semaphore(Runtime.getRuntime().availableProcessors());
    private static final int DEFAULT_JPEG_QUALITY = 90;
    private static final List<Double> WHITE_BACKGROUND = List.of(255.0, 255.0, 255.0);

    public ImageDimensions readDimensions(byte[] data) throws IOException {
        var ref = new AtomicReference<ImageDimensions>();
        runVips(arena -> {
            VImage img = VImage.newFromBytes(arena, data);
            ref.set(new ImageDimensions(img.getWidth(), img.getHeight()));
        });
        return ref.get();
    }

    public ImageDimensions readDimensionsFromFile(Path path) throws IOException {
        var ref = new AtomicReference<ImageDimensions>();
        runVips(arena -> {
            VImage img = VImage.newFromFile(arena, path.toString());
            ref.set(new ImageDimensions(img.getWidth(), img.getHeight()));
        });
        return ref.get();
    }

    public TrimBounds findContentBounds(byte[] data) throws IOException {
        var ref = new AtomicReference<TrimBounds>();
        runVips(arena -> {
            VImage img = VImage.newFromBytes(arena, data);
            if (img.hasAlpha()) {
                img = img.flatten(VipsOption.ArrayDouble("background", WHITE_BACKGROUND));
            }
            VImage.FindTrimOutput trim = img.findTrim(
                    VipsOption.Double("threshold", 30.0),
                    VipsOption.ArrayDouble("background", WHITE_BACKGROUND)
            );
            ref.set(new TrimBounds(trim.left(), trim.top(), trim.width(), trim.height()));
        });
        return ref.get();
    }

    public TrimBounds findContentBounds(Path path) throws IOException {
        var ref = new AtomicReference<TrimBounds>();
        runVips(arena -> {
            VImage img = VImage.newFromFile(arena, path.toString());
            if (img.hasAlpha()) {
                img = img.flatten(VipsOption.ArrayDouble("background", WHITE_BACKGROUND));
            }
            VImage.FindTrimOutput trim = img.findTrim(
                    VipsOption.Double("threshold", 30.0),
                    VipsOption.ArrayDouble("background", WHITE_BACKGROUND)
            );
            ref.set(new TrimBounds(trim.left(), trim.top(), trim.width(), trim.height()));
        });
        return ref.get();
    }

    public boolean canDecode(byte[] data) {
        if (data == null || data.length == 0) return false;
        try {
            Vips.run(arena -> VImage.newFromBytes(arena, data));
            return true;
        } catch (VipsError e) {
            return false;
        }
    }

    /**
     * Flatten alpha, proportional resize within bounds, save as JPEG.
     */
    public void flattenResizeAndSave(byte[] data, Path target, int maxWidth, int maxHeight) throws IOException {
        runVips(arena -> {
            VImage img = loadAndFlatten(arena, data);
            img = proportionalResize(img, maxWidth, maxHeight);
            img.jpegsave(target.toString(), VipsOption.Int("Q", DEFAULT_JPEG_QUALITY));
        });
    }

    /**
     * File-to-file: flatten alpha, proportional resize, save as JPEG.
     */
    public void flattenResizeAndSave(Path source, Path target, int maxWidth, int maxHeight) throws IOException {
        runVips(arena -> {
            VImage img = loadAndFlattenFromFile(arena, source.toString());
            img = proportionalResize(img, maxWidth, maxHeight);
            img.jpegsave(target.toString(), VipsOption.Int("Q", DEFAULT_JPEG_QUALITY));
        });
    }

    /**
     * Flatten alpha, crop to region, proportional resize, save as JPEG.
     */
    public void flattenCropResizeAndSave(byte[] data, Path target,
            int cropLeft, int cropTop, int cropWidth, int cropHeight,
            int maxWidth, int maxHeight) throws IOException {
        runVips(arena -> {
            VImage img = loadAndFlatten(arena, data);
            img = img.extractArea(cropLeft, cropTop, cropWidth, cropHeight);
            img = proportionalResize(img, maxWidth, maxHeight);
            img.jpegsave(target.toString(), VipsOption.Int("Q", DEFAULT_JPEG_QUALITY));
        });
    }

    /**
     * File-to-file: flatten alpha, crop, proportional resize, save as JPEG.
     */
    public void flattenCropResizeAndSave(Path source, Path target,
            int cropLeft, int cropTop, int cropWidth, int cropHeight,
            int maxWidth, int maxHeight) throws IOException {
        runVips(arena -> {
            VImage img = loadAndFlattenFromFile(arena, source.toString());
            img = img.extractArea(cropLeft, cropTop, cropWidth, cropHeight);
            img = proportionalResize(img, maxWidth, maxHeight);
            img.jpegsave(target.toString(), VipsOption.Int("Q", DEFAULT_JPEG_QUALITY));
        });
    }

    /**
     * Flatten alpha, smart-crop thumbnail, save as JPEG.
     */
    public void flattenThumbnailAndSave(byte[] data, Path target, int width, int height) throws IOException {
        runVips(arena -> {
            VImage img = loadAndFlatten(arena, data);
            img = thumbnailCrop(img, width, height);
            img.jpegsave(target.toString(), VipsOption.Int("Q", DEFAULT_JPEG_QUALITY));
        });
    }

    /**
     * File-to-file: flatten alpha, smart-crop thumbnail, save as JPEG.
     */
    public void flattenThumbnailAndSave(Path source, Path target, int width, int height) throws IOException {
        runVips(arena -> {
            VImage img = loadAndFlattenFromFile(arena, source.toString());
            img = thumbnailCrop(img, width, height);
            img.jpegsave(target.toString(), VipsOption.Int("Q", DEFAULT_JPEG_QUALITY));
        });
    }

    /**
     * Resizes an image efficiently using libvips's streaming pipeline.
     */
    public void resizeImage(Path source, Path target, int width, int height) throws IOException {
        runVips(arena -> {
            VImage img = VImage.newFromFile(arena, source.toString());
            if (img.getWidth() <= width && img.getHeight() <= height) {
                try {
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new VipsError("File copy failed: " + e.getMessage());
                }
                return;
            }
            VImage resized = proportionalResize(img, width, height);
            resized.jpegsave(target.toString(), VipsOption.Int("Q", DEFAULT_JPEG_QUALITY));
        });
    }

    /**
     * Generates a thumbnail with smart-cropping (center-weighted).
     */
    public void generateThumbnail(Path source, Path target, int width, int height) throws IOException {
        runVips(arena -> {
            VImage img = VImage.thumbnail(arena, source.toString(), width);
            img.jpegsave(target.toString(), VipsOption.Int("Q", DEFAULT_JPEG_QUALITY));
        });
    }

    /**
     * Crop a region from source, resize proportionally, save as JPEG.
     */
    public void cropResizeAndSave(Path source, Path target,
            int cropLeft, int cropTop, int cropWidth, int cropHeight,
            int maxWidth, int maxHeight) throws IOException {
        runVips(arena -> {
            VImage img = VImage.newFromFile(arena, source.toString());
            img = img.extractArea(cropLeft, cropTop, cropWidth, cropHeight);
            img = proportionalResize(img, maxWidth, maxHeight);
            img.jpegsave(target.toString(), VipsOption.Int("Q", DEFAULT_JPEG_QUALITY));
        });
    }

    /**
     * Encodes image bytes as JPEG with specified quality (0-100).
     */
    public byte[] encodeAsJpeg(byte[] data, int quality) throws IOException {
        var ref = new AtomicReference<byte[]>();
        runVips(arena -> {
            VImage img = VImage.newFromBytes(arena, data);
            VBlob blob = img.jpegsaveBuffer(VipsOption.Int("Q", quality));
            ref.set(blob.asClonedByteBuffer().array());
        });
        return ref.get();
    }

    /**
     * Encodes image bytes as PNG.
     */
    public byte[] encodeAsPng(byte[] data) throws IOException {
        var ref = new AtomicReference<byte[]>();
        runVips(arena -> {
            VImage img = VImage.newFromBytes(arena, data);
            VBlob blob = img.pngsaveBuffer();
            ref.set(blob.asClonedByteBuffer().array());
        });
        return ref.get();
    }

    /**
     * Converts a BufferedImage to JPEG bytes using ImageIO.
     * Works regardless of libvips availability since BufferedImages are already in Java heap.
     */
    public byte[] bufferedImageToJpeg(BufferedImage img, int quality) throws IOException {
        BufferedImage rgbImage;
        if (img.getType() == BufferedImage.TYPE_INT_RGB || img.getType() == BufferedImage.TYPE_3BYTE_BGR) {
            rgbImage = img;
        } else {
            rgbImage = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
            var g = rgbImage.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            g.drawImage(img, 0, 0, null);
            g.dispose();
        }
        var baos = new ByteArrayOutputStream();
        var writers = ImageIO.getImageWritersByFormatName("JPEG");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG writer available");
        }
        var writer = writers.next();
        try {
            var param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality / 100.0f);
            try (var ios = ImageIO.createImageOutputStream(baos)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(rgbImage, null, null), param);
            }
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }

    // ========================================
    // LEGACY API (kept for backward compat during migration)
    // ========================================

    /**
     * Converts an image to JPEG with optimized quality.
     * @deprecated Use {@link #encodeAsJpeg(byte[], int)} instead.
     */
    @Deprecated(forRemoval = true)
    public byte[] convertToOptimizedJpeg(byte[] data, float quality) throws IOException {
        return encodeAsJpeg(data, (int) (quality * 100));
    }

    // ========================================
    // INTERNAL HELPERS
    // ========================================

    private VImage loadAndFlatten(Arena arena, byte[] data) throws VipsError {
        VImage img = VImage.newFromBytes(arena, data);
        if (img.hasAlpha()) {
            img = img.flatten(VipsOption.ArrayDouble("background", WHITE_BACKGROUND));
        }
        return img;
    }

    private VImage loadAndFlattenFromFile(Arena arena, String path) throws VipsError {
        VImage img = VImage.newFromFile(arena, path);
        if (img.hasAlpha()) {
            img = img.flatten(VipsOption.ArrayDouble("background", WHITE_BACKGROUND));
        }
        return img;
    }

    private VImage proportionalResize(VImage img, int maxWidth, int maxHeight) throws VipsError {
        int w = img.getWidth();
        int h = img.getHeight();
        if (w <= maxWidth && h <= maxHeight) {
            return img;
        }
        double scale = Math.min((double) maxWidth / w, (double) maxHeight / h);
        return img.resize(scale);
    }

    private VImage thumbnailCrop(VImage img, int width, int height) throws VipsError {
        double scaleX = (double) width / img.getWidth();
        double scaleY = (double) height / img.getHeight();
        double scale = Math.max(scaleX, scaleY);
        if (scale < 1.0) {
            img = img.resize(scale);
        }
        int finalW = Math.min(img.getWidth(), width);
        int finalH = Math.min(img.getHeight(), height);
        int left = (img.getWidth() - finalW) / 2;
        int top = (img.getHeight() - finalH) / 2;
        if (left > 0 || top > 0) {
            img = img.extractArea(left, top, finalW, finalH);
        }
        return img;
    }

    private void runVips(VipsRunnable task) throws IOException {
        ENCODING_SEMAPHORE.acquireUninterruptibly();
        try {
            Vips.run(task::run);
        } catch (VipsError e) {
            throw new IOException("libvips operation failed", e);
        } finally {
            ENCODING_SEMAPHORE.release();
        }
    }

    @FunctionalInterface
    private interface VipsRunnable {
        void run(Arena arena) throws VipsError;
    }
}
