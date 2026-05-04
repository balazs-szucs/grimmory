package org.booklore.util;

import app.photofox.vipsffm.VBlob;
import app.photofox.vipsffm.VImage;
import app.photofox.vipsffm.VSource;
import app.photofox.vipsffm.VTarget;
import app.photofox.vipsffm.Vips;
import app.photofox.vipsffm.VipsError;
import app.photofox.vipsffm.VipsHelper;
import app.photofox.vipsffm.VipsOption;
import app.photofox.vipsffm.enums.VipsAccess;
import app.photofox.vipsffm.enums.VipsBandFormat;
import app.photofox.vipsffm.enums.VipsInteresting;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * High-performance image processing service using libvips via vips-ffm.
 * Prefers streaming (VSource/VTarget) and shrink-on-load (thumbnail) for memory efficiency.
 */
@Slf4j
@Service
public class VipsImageService {

    private static final Semaphore ENCODING_SEMAPHORE = new Semaphore(Runtime.getRuntime().availableProcessors() * 2);
    private static final int DEFAULT_JPEG_QUALITY = 90;
    private static final List<Double> WHITE_BACKGROUND = List.of(255.0, 255.0, 255.0);
    private static final VipsOption SEQUENTIAL_ACCESS = VipsOption.Enum("access", VipsAccess.ACCESS_SEQUENTIAL);

    /**
     * Read dimensions from raw bytes.
     */
    public ImageDimensions readDimensions(byte[] data) throws IOException {
        if (data == null || data.length == 0) throw new IOException("Image data is null or empty");
        var ref = new AtomicReference<ImageDimensions>();
        runVips(arena -> {
            VImage img = VImage.newFromBytes(arena, data, SEQUENTIAL_ACCESS);
            ref.set(new ImageDimensions(img.getWidth(), img.getHeight()));
        });
        return ref.get();
    }

    /**
     * Read dimensions from an InputStream without loading the full image into memory.
     */
    public ImageDimensions readDimensions(InputStream inputStream) throws IOException {
        if (inputStream == null) throw new IOException("Image stream is null");
        var ref = new AtomicReference<ImageDimensions>();
        runVips(arena -> {
            VSource source = VSource.newFromInputStream(arena, inputStream);
            VImage img = VImage.newFromSource(arena, source, "", SEQUENTIAL_ACCESS);
            ref.set(new ImageDimensions(img.getWidth(), img.getHeight()));
        });
        return ref.get();
    }

    /**
     * Read dimensions from a file efficiently.
     */
    public ImageDimensions readDimensionsFromFile(Path path) throws IOException {
        var ref = new AtomicReference<ImageDimensions>();
        runVips(arena -> {
            VImage img = VImage.newFromFile(arena, path.toString(), SEQUENTIAL_ACCESS);
            ref.set(new ImageDimensions(img.getWidth(), img.getHeight()));
        });
        return ref.get();
    }

    /**
     * Finds content bounds (trim) from bytes.
     */
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

    /**
     * Finds content bounds (trim) from a source file.
     */
    public TrimBounds findContentBounds(Path source) throws IOException {
        var ref = new AtomicReference<TrimBounds>();
        runVips(arena -> {
            VImage img = VImage.newFromFile(arena, source.toString(), SEQUENTIAL_ACCESS);
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

    /**
     * Checks if image bytes can be decoded.
     */
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
     * Checks if an image file can be decoded.
     */
    public boolean canDecode(Path path) {
        if (path == null) return false;
        try {
            Vips.run(arena -> VImage.newFromFile(arena, path.toString(), SEQUENTIAL_ACCESS));
            return true;
        } catch (VipsError e) {
            return false;
        }
    }

    /**
     * Checks if image stream can be decoded.
     */
    public boolean canDecode(InputStream inputStream) {
        if (inputStream == null) return false;
        try {
            Vips.run(arena -> {
                VSource source = VSource.newFromInputStream(arena, inputStream);
                VImage.newFromSource(arena, source, "", SEQUENTIAL_ACCESS);
            });
            return true;
        } catch (VipsError e) {
            return false;
        }
    }

    /**
     * Flatten alpha, proportional resize within bounds, save as JPEG to target path.
     * Uses thumbnail (shrink-on-load) for high performance.
     */
    public void flattenResizeAndSave(byte[] data, Path target, int maxWidth, int maxHeight) throws IOException {
        runVips(arena -> {
            VImage img = VImage.thumbnailBuffer(arena, VBlob.newFromBytes(arena, data), maxWidth, VipsOption.Int("height", maxHeight));
            if (img.hasAlpha()) {
                img = img.flatten(VipsOption.ArrayDouble("background", WHITE_BACKGROUND));
            }
            img.jpegsave(target.toString(), VipsOption.Int("Q", DEFAULT_JPEG_QUALITY));
        });
    }

    /**
     * File-to-file version of flattenResizeAndSave.
     */
    public void flattenResizeAndSave(Path source, Path target, int maxWidth, int maxHeight) throws IOException {
        runVips(arena -> {
            VImage img = VImage.thumbnail(arena, source.toString(), maxWidth, VipsOption.Int("height", maxHeight));
            if (img.hasAlpha()) {
                img = img.flatten(VipsOption.ArrayDouble("background", WHITE_BACKGROUND));
            }
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
            VImage img = VImage.newFromBytes(arena, data, SEQUENTIAL_ACCESS);
            if (img.hasAlpha()) {
                img = img.flatten(VipsOption.ArrayDouble("background", WHITE_BACKGROUND));
            }
            img = img.extractArea(cropLeft, cropTop, cropWidth, cropHeight);
            img = proportionalResize(img, maxWidth, maxHeight);
            img.jpegsave(target.toString(), VipsOption.Int("Q", DEFAULT_JPEG_QUALITY));
        });
    }

    /**
     * File-to-file version of flattenCropResizeAndSave.
     */
    public void flattenCropResizeAndSave(Path source, Path target,
            int cropLeft, int cropTop, int cropWidth, int cropHeight,
            int maxWidth, int maxHeight) throws IOException {
        runVips(arena -> {
            VImage img = VImage.newFromFile(arena, source.toString(), SEQUENTIAL_ACCESS);
            if (img.hasAlpha()) {
                img = img.flatten(VipsOption.ArrayDouble("background", WHITE_BACKGROUND));
            }
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
            VImage img = VImage.thumbnailBuffer(arena, VBlob.newFromBytes(arena, data), width,
                    VipsOption.Int("height", height),
                    VipsOption.Enum("crop", VipsInteresting.INTERESTING_CENTRE));
            if (img.hasAlpha()) {
                img = img.flatten(VipsOption.ArrayDouble("background", WHITE_BACKGROUND));
            }
            img.jpegsave(target.toString(), VipsOption.Int("Q", DEFAULT_JPEG_QUALITY));
        });
    }

    /**
     * File-to-file version of flattenThumbnailAndSave.
     */
    public void flattenThumbnailAndSave(Path source, Path target, int width, int height) throws IOException {
        runVips(arena -> {
            VImage img = VImage.thumbnail(arena, source.toString(), width,
                    VipsOption.Int("height", height),
                    VipsOption.Enum("crop", VipsInteresting.INTERESTING_CENTRE));
            if (img.hasAlpha()) {
                img = img.flatten(VipsOption.ArrayDouble("background", WHITE_BACKGROUND));
            }
            img.jpegsave(target.toString(), VipsOption.Int("Q", DEFAULT_JPEG_QUALITY));
        });
    }

    /**
     * Crop a region from source file, resize proportionally, save as JPEG.
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
     * Converts a BufferedImage to JPEG bytes using libvips.
     */
    public byte[] bufferedImageToJpeg(BufferedImage img, int quality) throws IOException {
        int w = img.getWidth();
        int h = img.getHeight();
        byte[] rgbBytes = extractRgbBytes(img, w, h);
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
     * Downscales a BufferedImage and encodes directly to JPEG.
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
            if (scale < 1.0) {
                vimg = vimg.resize(scale);
            }
            VBlob blob = vimg.jpegsaveBuffer(VipsOption.Int("Q", quality));
            ref.set(blob.asClonedByteBuffer().array());
        });
        return ref.get();
    }

    /**
     * Processes an image from an InputStream and writes it to an OutputStream as optimized JPEG.
     */
    public void processStreamToJpeg(InputStream inputStream, OutputStream outputStream, int maxWidth, int maxHeight) throws IOException {
        runVips(arena -> {
            VSource source = VSource.newFromInputStream(arena, inputStream);
            // Use thumbnail for streaming load + resize
            VImage img = VImage.thumbnailSource(arena, source, maxWidth, VipsOption.Int("height", maxHeight));
            if (img.hasAlpha()) {
                img = img.flatten(VipsOption.ArrayDouble("background", WHITE_BACKGROUND));
            }
            VTarget target = VTarget.newFromOutputStream(arena, outputStream);
            img.jpegsaveTarget(target, VipsOption.Int("Q", DEFAULT_JPEG_QUALITY));
        });
    }

    /**
     * Transcodes a source stream to JPEG without materializing full byte[] buffers.
     */
    public void transcodeStreamToJpeg(InputStream inputStream, OutputStream outputStream, int quality) throws IOException {
        int effectiveQuality = Math.max(1, Math.min(100, quality));
        runVips(arena -> {
            VSource source = VSource.newFromInputStream(arena, inputStream);
            VImage img = VImage.newFromSource(arena, source, "", SEQUENTIAL_ACCESS);
            if (img.hasAlpha()) {
                img = img.flatten(VipsOption.ArrayDouble("background", WHITE_BACKGROUND));
            }
            VTarget target = VTarget.newFromOutputStream(arena, outputStream);
            img.jpegsaveTarget(target, VipsOption.Int("Q", effectiveQuality));
        });
    }

    private byte[] extractRgbBytes(BufferedImage image, int w, int h) {
        // If it's already INT_RGB or 3BYTE_BGR, we could potentially get the DataBuffer,
        // but getRGB is safest for compatibility across all types.
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

    private void runVips(VipsRunnable task) throws IOException {
        if (!org.booklore.nativelib.NativeLibraries.get().isVipsAvailable()) {
            throw new IOException("libvips native library is not available");
        }
        ENCODING_SEMAPHORE.acquireUninterruptibly();
        try {
            Vips.run(task::run);
        } catch (VipsError e) {
            throw new IOException("libvips operation failed: " + e.getMessage(), e);
        } finally {
            ENCODING_SEMAPHORE.release();
        }
    }

    @FunctionalInterface
    private interface VipsRunnable {
        void run(Arena arena) throws VipsError;
    }
}
