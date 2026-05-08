package org.booklore.util;

import app.photofox.vipsffm.*;
import app.photofox.vipsffm.enums.VipsBandFormat;
import org.grimmory.pdfium4j.PdfPage;
import org.grimmory.pdfium4j.PdfDocument;
import org.grimmory.pdfium4j.model.RenderResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.io.*;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;

@Slf4j
@Service
public class VipsImageService {
    static { Vips.init(); }

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
        try { return readDimensions(data) != null; } catch (Exception _) { return false; }
    }

    public boolean canDecode(Path path) {
        try { return readDimensionsFromFile(path) != null; } catch (Exception _) { return false; }
    }

    public boolean canDecode(InputStream is) {
        try { return readDimensions(is) != null; } catch (Exception _) { return false; }
    }

    public void flattenResizeAndSave(byte[] data, Path out, int maxW, int maxH) throws IOException {
        runWithArena(arena -> {
            VImage img = VImage.newFromBytes(arena, data).flatten();
            double scale = Math.min(1.0, Math.min((double) maxW / img.getWidth(), (double) maxH / img.getHeight()));
            if (scale < 1.0) img = img.resize(scale);
            img.jpegsave(out.toString(), VipsOption.Int("Q", 85), VipsOption.Boolean("strip", true), VipsOption.Boolean("optimize_coding", true));
            return null;
        });
    }

    public void flattenResizeAndSave(Path in, Path out, int maxW, int maxH) throws IOException {
        runWithArena(arena -> {
            VImage img = VImage.newFromFile(arena, in.toString()).flatten();
            double scale = Math.min(1.0, Math.min((double) maxW / img.getWidth(), (double) maxH / img.getHeight()));
            if (scale < 1.0) img = img.resize(scale);
            img.jpegsave(out.toString(), VipsOption.Int("Q", 85), VipsOption.Boolean("strip", true), VipsOption.Boolean("optimize_coding", true));
            return null;
        });
    }

    public void cropResizeAndSave(Path in, Path out, int x, int y, int w, int h, int targetW, int targetH) throws IOException {
        runWithArena(arena -> {
            VImage img = VImage.newFromFile(arena, in.toString()).extractArea(x, y, w, h);
            if (w != targetW || h != targetH) img = img.resize((double) targetW / w);
            img.jpegsave(out.toString(), VipsOption.Int("Q", 85), VipsOption.Boolean("strip", true));
            return null;
        });
    }

    public void flattenCropResizeAndSave(Path in, Path out, int x, int y, int w, int h, int targetW, int targetH) throws IOException {
        runWithArena(arena -> {
            VImage img = VImage.newFromFile(arena, in.toString()).flatten().extractArea(x, y, w, h);
            img = img.resize((double) targetW / w);
            img.jpegsave(out.toString(), VipsOption.Int("Q", 85), VipsOption.Boolean("strip", true));
            return null;
        });
    }

    public void flattenThumbnailAndSave(Path in, Path out, int w, int h) throws IOException {
        runWithArena(arena -> {
            VImage.thumbnail(arena, in.toString(), w, VipsOption.Int("height", h), VipsOption.String("crop", "centre"))
                 .flatten().jpegsave(out.toString(), VipsOption.Int("Q", 80), VipsOption.Boolean("strip", true));
            return null;
        });
    }

    public void processStreamToJpeg(InputStream is, OutputStream os, int maxW, int maxH) throws IOException {
        runWithArena(arena -> {
            VImage img = VImage.newFromSource(arena, VSource.newFromInputStream(arena, is)).flatten();
            double scale = Math.min(1.0, Math.min((double) maxW / img.getWidth(), (double) maxH / img.getHeight()));
            if (scale < 1.0) img = img.resize(scale);
            img.jpegsaveTarget(VTarget.newFromOutputStream(arena, os), VipsOption.Int("Q", 85), VipsOption.Boolean("strip", true));
            return null;
        });
    }

    public void transcodeStreamToJpeg(InputStream is, OutputStream os, int quality) throws IOException {
        runWithArena(arena -> {
            VImage img = VImage.newFromSource(arena, VSource.newFromInputStream(arena, is));
            img.jpegsaveTarget(VTarget.newFromOutputStream(arena, os), VipsOption.Int("Q", quality), VipsOption.Boolean("strip", true));
            return null;
        });
    }

    public byte[] encodeAsPng(byte[] data) throws IOException {
        return runWithArena(arena -> VImage.newFromBytes(arena, data).pngsaveBuffer().getBytes());
    }

    public byte[] bufferedImageToJpeg(java.awt.image.BufferedImage img, int q) throws IOException {
        return runWithArena(arena -> bufferedImageToVips(arena, img).jpegsaveBuffer(VipsOption.Int("Q", q), VipsOption.Boolean("strip", true)).getBytes());
    }

    public byte[] downscaleBufferedImageToJpeg(java.awt.image.BufferedImage img, int maxW, int maxH, int q) throws IOException {
        return runWithArena(arena -> {
            VImage vimg = bufferedImageToVips(arena, img);
            double scale = Math.min(1.0, Math.min((double) maxW / vimg.getWidth(), (double) maxH / vimg.getHeight()));
            if (scale < 1.0) vimg = vimg.resize(scale);
            return vimg.jpegsaveBuffer(VipsOption.Int("Q", q), VipsOption.Boolean("strip", true)).getBytes();
        });
    }

    private VImage bufferedImageToVips(Arena arena, java.awt.image.BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        byte[] pixels = new byte[w * h * 3];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                pixels[(y * w + x) * 3] = (byte)((rgb >> 16) & 0xFF);
                pixels[(y * w + x) * 3 + 1] = (byte)((rgb >> 8) & 0xFF);
                pixels[(y * w + x) * 3 + 2] = (byte)(rgb & 0xFF);
            }
        }
        MemorySegment segment = arena.allocate(pixels.length);
        MemorySegment.copy(pixels, 0, segment, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, pixels.length);
        return VImage.newFromMemory(arena, segment, w, h, 3, 22); // INTERPRETATION_SRGB = 22
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
            VImage vimg = VImage.newFromMemory(arena, segment, w, h, 4, VipsBandFormat.FORMAT_UCHAR.getRawValue());
            vimg = vimg.flatten(); // Remove alpha for JPEG
            return vimg.jpegsaveBuffer(VipsOption.Int("Q", quality), VipsOption.Boolean("strip", true)).getBytes();
        });
    }

    public TrimBounds findContentBounds(byte[] data) throws IOException {
        return runWithArena(arena -> {
            VImage img = VImage.newFromBytes(arena, data);
            var output = img.findTrim(VipsOption.Int("threshold", 10));
            return new TrimBounds(output.left(), output.top(), output.width(), output.height());
        });
    }

    public TrimBounds findContentBounds(Path path) throws IOException {
        return runWithArena(arena -> {
            VImage img = VImage.newFromFile(arena, path.toString());
            var output = img.findTrim(VipsOption.Int("threshold", 10));
            return new TrimBounds(output.left(), output.top(), output.width(), output.height());
        });
    }

    private <T> T runWithArena(VipsCallable<T> callable) throws IOException {
        try (Arena arena = Arena.ofConfined()) { return callable.call(arena); }
        catch (VipsError e) { throw new IOException(e.getMessage(), e); }
        catch (Exception e) { throw new IOException(e); }
    }

    @FunctionalInterface private interface VipsCallable<T> { T call(Arena arena) throws Exception; }
}
