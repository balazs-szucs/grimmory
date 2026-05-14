package org.booklore.util;

import app.photofox.vipsffm.VBlob;
import app.photofox.vipsffm.VImage;
import app.photofox.vipsffm.VSource;
import app.photofox.vipsffm.VTarget;
import app.photofox.vipsffm.Vips;
import app.photofox.vipsffm.VipsError;
import app.photofox.vipsffm.VipsOption;
import app.photofox.vipsffm.enums.VipsBandFormat;
import app.photofox.vipsffm.enums.VipsInterpretation;
import lombok.extern.slf4j.Slf4j;
import org.grimmory.pdfium4j.PdfPage;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;

@Slf4j
@Service
public class VipsImageService {
    public VipsImageService() {
        try {
            Vips.init();
            // Best practice: disable operation cache for long-running servers to save memory
            Vips.disableOperationCache();
            if (log.isDebugEnabled()) {
                Vips.enableLeakDetection();
            }
        } catch (Throwable t) {
            log.warn("libvips native library not available: {}", t.toString());
        }
    }

    public ImageDimensions readDimensions(byte[] data) throws IOException {
        return runWithArena(arena -> {
            VImage img = VImage.newFromBytes(arena, data);
            return new ImageDimensions(img.getWidth(), img.getHeight());
        });
    }

    public ImageDimensions readDimensions(InputStream is) throws IOException {
        return runWithArena(arena -> {
            VImage img = VImage.newFromSource(arena, VSource.newFromInputStream(arena, is));
            return new ImageDimensions(img.getWidth(), img.getHeight());
        });
    }

    public ImageDimensions readDimensionsFromFile(Path path) throws IOException {
        return runWithArena(arena -> {
            VImage img = VImage.newFromFile(arena, path.toString());
            return new ImageDimensions(img.getWidth(), img.getHeight());
        });
    }

    public boolean canDecode(byte[] data) {
        try {
            return readDimensions(data) != null;
        } catch (Exception _) {
            return false;
        }
    }

    public boolean canDecode(Path path) {
        try {
            return readDimensionsFromFile(path) != null;
        } catch (Exception _) {
            return false;
        }
    }

    public boolean canDecode(InputStream is) {
        try {
            return readDimensions(is) != null;
        } catch (Exception _) {
            return false;
        }
    }

    public void flattenResizeAndSave(byte[] data, Path out, int maxW, int maxH) throws IOException {
        runWithArena(arena -> {
            // Best practice: use thumbnailBuffer for efficient shrink-on-load. thumbnail already handles autorotate.
            VBlob blob = VBlob.newFromBytes(arena, data);
            VImage img = VImage.thumbnailBuffer(arena, blob, maxW, VipsOption.Int("height", maxH))
                    .flatten();
            img.jpegsave(out.toString(), VipsOption.Int("Q", 85), VipsOption.Boolean("strip", true), VipsOption.Boolean("optimize_coding", true));
            return null;
        });
    }

    public void flattenResizeAndSave(Path in, Path out, int maxW, int maxH) throws IOException {
        runWithArena(arena -> {
            // Best practice: use thumbnail for efficient shrink-on-load. thumbnail already handles autorotate.
            VImage img = VImage.thumbnail(arena, in.toString(), maxW, VipsOption.Int("height", maxH))
                    .flatten();
            img.jpegsave(out.toString(), VipsOption.Int("Q", 85), VipsOption.Boolean("strip", true), VipsOption.Boolean("optimize_coding", true));
            return null;
        });
    }

    public void cropResizeAndSave(Path in, Path out, int x, int y, int w, int h, int targetW, int targetH) throws IOException {
        runWithArena(arena -> {
            VImage img = VImage.newFromFile(arena, in.toString()).autorot().extractArea(x, y, w, h);
            if (w != targetW || h != targetH) img = img.resize((double) targetW / w);
            img.jpegsave(out.toString(), VipsOption.Int("Q", 85), VipsOption.Boolean("strip", true), VipsOption.Boolean("optimize_coding", true));
            return null;
        });
    }

    public void flattenCropResizeAndSave(Path in, Path out, int x, int y, int w, int h, int targetW, int targetH) throws IOException {
        runWithArena(arena -> {
            VImage img = VImage.newFromFile(arena, in.toString()).autorot().flatten().extractArea(x, y, w, h);
            double scale = Math.min((double) targetW / w, (double) targetH / h);
            img = img.resize(scale);
            img.jpegsave(out.toString(), VipsOption.Int("Q", 85), VipsOption.Boolean("strip", true), VipsOption.Boolean("optimize_coding", true));
            return null;
        });
    }

    public void flattenThumbnailAndSave(Path in, Path out, int w, int h) throws IOException {
        runWithArena(arena -> {
            VImage.thumbnail(arena, in.toString(), w, VipsOption.Int("height", h), VipsOption.String("crop", "centre"))
                    .flatten().jpegsave(out.toString(), VipsOption.Int("Q", 80), VipsOption.Boolean("strip", true), VipsOption.Boolean("optimize_coding", true));
            return null;
        });
    }

    public void processStreamToJpeg(InputStream is, OutputStream os, int maxW, int maxH) throws IOException {
        runWithArena(arena -> {
            // Best practice: use thumbnailSource for efficient shrink-on-load from stream. thumbnail already handles autorotate.
            VSource source = VSource.newFromInputStream(arena, is);
            VImage img = VImage.thumbnailSource(arena, source, maxW, VipsOption.Int("height", maxH))
                    .flatten();
            img.jpegsaveTarget(VTarget.newFromOutputStream(arena, os), VipsOption.Int("Q", 85), VipsOption.Boolean("strip", true), VipsOption.Boolean("optimize_coding", true));
            return null;
        });
    }

    public void transcodeStreamToJpeg(InputStream is, OutputStream os, int quality) throws IOException {
        runWithArena(arena -> {
            VImage img = VImage.newFromSource(arena, VSource.newFromInputStream(arena, is)).autorot();
            img.jpegsaveTarget(VTarget.newFromOutputStream(arena, os), VipsOption.Int("Q", quality), VipsOption.Boolean("strip", true), VipsOption.Boolean("optimize_coding", true));
            return null;
        });
    }

    public byte[] encodeAsPng(byte[] data) throws IOException {
        return runWithArena(arena -> VImage.newFromBytes(arena, data).autorot().pngsaveBuffer().getBytes());
    }

    public byte[] bufferedImageToJpeg(BufferedImage img, int q) throws IOException {
        return runWithArena(arena -> bufferedImageToVips(arena, img).jpegsaveBuffer(VipsOption.Int("Q", q), VipsOption.Boolean("strip", true), VipsOption.Boolean("optimize_coding", true)).getBytes());
    }

    public byte[] downscaleAndEncodeJpeg(BufferedImage img, int targetW, int targetH, int q) throws IOException {
        return runWithArena(arena -> {
            VImage vimg = bufferedImageToVips(arena, img);
            if (vimg.getWidth() != targetW || vimg.getHeight() != targetH) {
                // Use thumbnailImage for high-quality downscaling of an in-memory image
                vimg = vimg.thumbnailImage(targetW, VipsOption.Int("height", targetH));
            }
            return vimg.jpegsaveBuffer(VipsOption.Int("Q", q), VipsOption.Boolean("strip", true), VipsOption.Boolean("optimize_coding", true)).getBytes();
        });
    }

    private VImage bufferedImageToVips(Arena arena, BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        byte[] pixels = new byte[w * h * 3];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                pixels[(y * w + x) * 3] = (byte) ((rgb >> 16) & 0xFF);
                pixels[(y * w + x) * 3 + 1] = (byte) ((rgb >> 8) & 0xFF);
                pixels[(y * w + x) * 3 + 2] = (byte) (rgb & 0xFF);
            }
        }
        MemorySegment segment = arena.allocate(pixels.length);
        MemorySegment.copy(pixels, 0, segment, ValueLayout.JAVA_BYTE, 0, pixels.length);
        return VImage.newFromMemory(arena, segment, w, h, 3, VipsBandFormat.FORMAT_UCHAR.getRawValue())
                .copy(VipsOption.Enum("interpretation", VipsInterpretation.INTERPRETATION_sRGB));
    }

    public byte[] renderPageToJpeg(PdfPage page, int dpi, int quality) throws IOException {
        return runWithArena(arena -> {
            var size = page.size();
            int w = size.widthPixels(dpi);
            int h = size.heightPixels(dpi);
            int stride = w * 4;
            MemorySegment segment = arena.allocate((long) stride * h);
            page.renderTo(segment, w, h, stride, 0, (int) 0xFFFFFFFF); // OPAQUE_WHITE

            // PDFium rendered in RGBA format (with FPDF_REVERSE_BYTE_ORDER which is default in PDFium4j)
            VImage vimg = VImage.newFromMemory(arena, segment, w, h, 4, VipsBandFormat.FORMAT_UCHAR.getRawValue())
                    .copy(VipsOption.Enum("interpretation", VipsInterpretation.INTERPRETATION_sRGB));
            vimg = vimg.flatten(); // Remove alpha for JPEG
            return vimg.jpegsaveBuffer(VipsOption.Int("Q", quality), VipsOption.Boolean("strip", true), VipsOption.Boolean("optimize_coding", true)).getBytes();
        });
    }

    public TrimBounds findContentBounds(byte[] data) throws IOException {
        return runWithArena(arena -> {
            VImage img = VImage.newFromBytes(arena, data).autorot();
            var output = img.findTrim(VipsOption.Int("threshold", 10));
            return new TrimBounds(output.left(), output.top(), output.width(), output.height());
        });
    }

    public TrimBounds findContentBounds(Path path) throws IOException {
        return runWithArena(arena -> {
            VImage img = VImage.newFromFile(arena, path.toString()).autorot();
            var output = img.findTrim(VipsOption.Int("threshold", 10));
            return new TrimBounds(output.left(), output.top(), output.width(), output.height());
        });
    }

    private <T> T runWithArena(VipsCallable<T> callable) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            return callable.call(arena);
        } catch (VipsError e) {
            throw new IOException(e.getMessage(), e);
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    @FunctionalInterface
    private interface VipsCallable<T> {
        T call(Arena arena) throws Exception;
    }
}
