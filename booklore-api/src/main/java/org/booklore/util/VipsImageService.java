package org.booklore.util;

import app.photofox.vipsffm.VBlob;
import app.photofox.vipsffm.VImage;
import app.photofox.vipsffm.Vips;
import app.photofox.vipsffm.VipsError;
import app.photofox.vipsffm.VipsHelper;
import app.photofox.vipsffm.VipsOption;
import app.photofox.vipsffm.enums.VipsAccess;
import app.photofox.vipsffm.enums.VipsBandFormat;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static final VipsOption SEQUENTIAL_ACCESS = VipsOption.Enum("access", VipsAccess.ACCESS_SEQUENTIAL);
    private static final AtomicBoolean VIPS_CONFIGURED = new AtomicBoolean(false);

    public ImageDimensions readDimensions(byte[] data) throws IOException {
        var ref = new AtomicReference<ImageDimensions>();
        runVips(arena -> {
            VImage img = VImage.newFromBytes(arena, data, SEQUENTIAL_ACCESS);
            ref.set(new ImageDimensions(img.getWidth(), img.getHeight()));
        });
        return ref.get();
    }

    public ImageDimensions readDimensionsFromFile(Path path) throws IOException {
        var ref = new AtomicReference<ImageDimensions>();
        runVips(arena -> {
            VImage img = VImage.newFromFile(arena, path.toString(), SEQUENTIAL_ACCESS);
            ref.set(new ImageDimensions(img.getWidth(), img.getHeight()));
        });
        return ref.get();
    }

    public TrimBounds findContentBounds(byte[] data) throws IOException {
        var ref = new AtomicReference<TrimBounds>();
        runVips(arena -> {
            VImage img = VImage.newFromBytes(arena, data, SEQUENTIAL_ACCESS);
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
            VImage img = VImage.newFromFile(arena, path.toString(), SEQUENTIAL_ACCESS);
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
            Vips.run(arena -> VImage.newFromBytes(arena, data, SEQUENTIAL_ACCESS));
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
            VImage img = VImage.newFromFile(arena, source.toString(), SEQUENTIAL_ACCESS);
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
            VImage img = VImage.newFromFile(arena, source.toString(), SEQUENTIAL_ACCESS);
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
            VImage img = VImage.newFromBytes(arena, data, SEQUENTIAL_ACCESS);
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
            VImage img = VImage.newFromBytes(arena, data, SEQUENTIAL_ACCESS);
            VBlob blob = img.pngsaveBuffer();
            ref.set(blob.asClonedByteBuffer().array());
        });
        return ref.get();
    }

    /**
     * Converts a BufferedImage to JPEG bytes using libvips via vips-ffm.
     * Extracts raw RGB pixel data from the BufferedImage and encodes it with
     * libvips's optimised JPEG encoder (libjpeg-turbo).
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
        int w = rgbImage.getWidth();
        int h = rgbImage.getHeight();
        byte[] rgbBytes = extractRgbBytes(rgbImage, w, h);
        var ref = new AtomicReference<byte[]>();
        runVips(arena -> {
            MemorySegment segment = arena.allocate((long) w * h * 3);
            segment.copyFrom(MemorySegment.ofArray(rgbBytes));
            VImage vimg = VImage.newFromMemory(arena, segment, w, h, 3,
                    VipsBandFormat.FORMAT_UCHAR.getRawValue());
            VBlob blob = vimg.jpegsaveBuffer(VipsOption.Int("Q", quality));
            ref.set(blob.asClonedByteBuffer().array());
        });
        return ref.get();
    }

    /**
     * Downscales a BufferedImage using vips Lanczos3 and encodes directly to JPEG.
     * Avoids creating an intermediate BufferedImage by combining resize + encode
     * in a single vips pipeline.
     */
    public byte[] downscaleBufferedImageToJpeg(BufferedImage img, int targetWidth, int targetHeight,
            int quality) throws IOException {
        int w = img.getWidth();
        int h = img.getHeight();
        byte[] rgbBytes = extractRgbBytes(img, w, h);
        var ref = new AtomicReference<byte[]>();
        runVips(arena -> {
            MemorySegment segment = arena.allocate((long) w * h * 3);
            segment.copyFrom(MemorySegment.ofArray(rgbBytes));
            VImage vimg = VImage.newFromMemory(arena, segment, w, h, 3,
                    VipsBandFormat.FORMAT_UCHAR.getRawValue());
            double scale = Math.min((double) targetWidth / w, (double) targetHeight / h);
            vimg = vimg.resize(scale);
            VBlob blob = vimg.jpegsaveBuffer(VipsOption.Int("Q", quality));
            ref.set(blob.asClonedByteBuffer().array());
        });
        return ref.get();
    }

    /**
     * Converts an image to JPEG with optimized quality.
     * @deprecated Use {@link #encodeAsJpeg(byte[], int)} instead.
     */
    @Deprecated(forRemoval = true)
    public byte[] convertToOptimizedJpeg(byte[] data, float quality) throws IOException {
        return encodeAsJpeg(data, (int) (quality * 100));
    }

    private VImage loadAndFlatten(Arena arena, byte[] data) throws VipsError {
        VImage img = VImage.newFromBytes(arena, data, SEQUENTIAL_ACCESS);
        if (img.hasAlpha()) {
            img = img.flatten(VipsOption.ArrayDouble("background", WHITE_BACKGROUND));
        }
        return img;
    }

    private VImage loadAndFlattenFromFile(Arena arena, String path) throws VipsError {
        VImage img = VImage.newFromFile(arena, path, SEQUENTIAL_ACCESS);
        if (img.hasAlpha()) {
            img = img.flatten(VipsOption.ArrayDouble("background", WHITE_BACKGROUND));
        }
        return img;
    }

    private byte[] extractRgbBytes(BufferedImage image, int w, int h) {
        int[] pixels = image.getRGB(0, 0, w, h, null, 0, w);
        byte[] rgbBytes = new byte[w * h * 3];
        for (int i = 0; i < pixels.length; i++) {
            int px = pixels[i];
            rgbBytes[i * 3]     = (byte) ((px >> 16) & 0xFF);
            rgbBytes[i * 3 + 1] = (byte) ((px >> 8) & 0xFF);
            rgbBytes[i * 3 + 2] = (byte) (px & 0xFF);
        }
        return rgbBytes;
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
            Vips.run(arena -> {
                if (VIPS_CONFIGURED.compareAndSet(false, true)) {
                    try {
                        VipsHelper.cache_set_max(0);
                        log.info("libvips {} initialised – operation cache disabled",
                                VipsHelper.version_string());
                    } catch (VipsError e) {
                        log.warn("Failed to configure libvips cache settings", e);
                    }
                }
                task.run(arena);
            });
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
