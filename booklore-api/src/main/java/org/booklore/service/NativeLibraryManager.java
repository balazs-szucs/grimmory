package org.booklore.service;

import com.github.gotson.nightcompress.Archive;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.grimmory.pdfium4j.PdfiumLibrary;
import org.springframework.stereotype.Component;

import java.util.concurrent.StructuredTaskScope;

/**
 * Eagerly initializes all native libraries at startup and exposes
 * availability flags.
 *
 * <p><b>Why this exists:</b> PDFium, libarchive (NightCompress), and
 * epub4j-native (gumbo / pugixml) are loaded via Java's Foreign Function &amp;
 * Memory API.  Their native initialisation routines are <em>not</em>
 * thread-safe.  If two threads race to load the same library the JVM crashes
 * with a SIGSEGV inside libc.  Even after loading, PDFium explicitly documents
 * that {@code PdfDocument} must not be used concurrently.</p>
 *
 * <p>Probes run in parallel via {@link StructuredTaskScope} (Java 25 preview)
 * to shave startup time.  Thread-safety for <em>usage</em> is handled by
 * dedicated single-thread executors in {@link PdfiumNativeService} and
 * {@link EpubNativeService}.</p>
 */
@Slf4j
@Component
public class NativeLibraryManager {

    private volatile boolean pdfiumAvailable;
    private volatile boolean libArchiveAvailable;
    private volatile boolean epubNativeAvailable;

    /** Default constructor used by Spring. */
    public NativeLibraryManager() {}

    /** Test constructor that accepts pre-probed availability flags. */
    public NativeLibraryManager(boolean pdfiumAvailable, boolean libArchiveAvailable,
                                boolean epubNativeAvailable) {
        this.pdfiumAvailable = pdfiumAvailable;
        this.libArchiveAvailable = libArchiveAvailable;
        this.epubNativeAvailable = epubNativeAvailable;
    }

    @PostConstruct
    void initAll() throws InterruptedException {
        try (var scope = StructuredTaskScope.open()) {
            var pdfTask = scope.fork(() -> probePdfium());
            var archTask = scope.fork(() -> probeLibArchive());
            var epubTask = scope.fork(() -> probeEpubNative());
            scope.join();

            pdfiumAvailable = pdfTask.get();
            libArchiveAvailable = archTask.get();
            epubNativeAvailable = epubTask.get();
        }

        log.info("Native libraries — PDFium: {}, libarchive: {}, epub4j-native: {}",
                flag(pdfiumAvailable), flag(libArchiveAvailable), flag(epubNativeAvailable));
    }

    public boolean isPdfiumAvailable()     { return pdfiumAvailable; }
    public boolean isLibArchiveAvailable() { return libArchiveAvailable; }
    public boolean isEpubNativeAvailable() { return epubNativeAvailable; }

    private static boolean probePdfium() {
        try {
            PdfiumLibrary.initialize();
            return true;
        } catch (Exception | UnsatisfiedLinkError e) {
            log.warn("PDFium native library not available: {}", e.getMessage());
            return false;
        }
    }

    private static boolean probeLibArchive() {
        try {
            return Archive.isAvailable();
        } catch (Throwable e) {
            log.warn("libarchive not available: {}", e.getMessage());
            return false;
        }
    }

    private static boolean probeEpubNative() {
        try {
            // Force class loading of NativeArchive which triggers the native
            // library lookup for gumbo / pugixml.
            Class.forName("org.grimmory.epub4j.native_parsing.NativeArchive");
            return true;
        } catch (Throwable e) {
            log.warn("epub4j-native not available: {}", e.getMessage());
            return false;
        }
    }

    private static String flag(boolean ok) {
        return ok ? "loaded" : "NOT available";
    }
}
