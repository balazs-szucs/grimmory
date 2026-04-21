package org.booklore.test;

import org.booklore.service.EpubNativeService;
import org.booklore.service.NativeLibraryManager;
import org.booklore.service.PdfiumNativeService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Static holder that eagerly initialises all native libraries exactly once,
 * for use in unit tests that run <em>without</em> a Spring context.
 *
 * <p>Spring-managed tests get the same guarantee via
 * {@link org.booklore.service.NativeLibraryManager}; this class covers the
 * plain-Mockito / JUnit tests.</p>
 */
public final class NativeLibraryInitializer {

    private static volatile boolean pdfiumAvailable;
    private static volatile boolean libArchiveAvailable;
    private static volatile boolean epubNativeAvailable;
    private static volatile boolean initialised;

    // Shared single-thread executors for tests, daemon threads so they
    // don't keep the JVM alive after the test suite finishes.
    private static final ExecutorService PDFIUM_EXECUTOR =
            Executors.newSingleThreadExecutor(Thread.ofPlatform()
                    .name("test-pdfium-worker").daemon(true).factory());
    private static final ExecutorService EPUB_NATIVE_EXECUTOR =
            Executors.newSingleThreadExecutor(Thread.ofPlatform()
                    .name("test-epub-native-worker").daemon(true).factory());

    private NativeLibraryInitializer() {}

    /** Call from {@code @BeforeAll} or a static initialiser. Idempotent. */
    public static synchronized void ensureInitialised() {
        if (initialised) return;
        pdfiumAvailable = probePdfium();
        libArchiveAvailable = probeLibArchive();
        epubNativeAvailable = probeEpubNative();
        initialised = true;
    }

    public static boolean isPdfiumAvailable()     { ensureInitialised(); return pdfiumAvailable; }
    public static boolean isLibArchiveAvailable() { ensureInitialised(); return libArchiveAvailable; }
    public static boolean isEpubNativeAvailable() { ensureInitialised(); return epubNativeAvailable; }

    /**
     * Creates a {@link NativeLibraryManager} that is pre-initialised with the
     * same probing results as this static holder.
     */
    public static NativeLibraryManager createManager() {
        ensureInitialised();
        return new NativeLibraryManager(pdfiumAvailable, libArchiveAvailable, epubNativeAvailable);
    }

    /** Creates a {@link PdfiumNativeService} backed by a shared test executor. */
    public static PdfiumNativeService createPdfiumNativeService() {
        return new PdfiumNativeService(PDFIUM_EXECUTOR, createManager());
    }

    /** Creates an {@link EpubNativeService} backed by a shared test executor. */
    public static EpubNativeService createEpubNativeService() {
        return new EpubNativeService(EPUB_NATIVE_EXECUTOR, createManager());
    }

    private static boolean probePdfium() {
        try {
            org.grimmory.pdfium4j.PdfiumLibrary.initialize();
            return true;
        } catch (Exception | UnsatisfiedLinkError e) {
            return false;
        }
    }

    private static boolean probeLibArchive() {
        try {
            return com.github.gotson.nightcompress.Archive.isAvailable();
        } catch (Throwable e) {
            return false;
        }
    }

    private static boolean probeEpubNative() {
        try {
            Class.forName("org.grimmory.epub4j.native_parsing.NativeArchive");
            return true;
        } catch (Throwable e) {
            return false;
        }
    }
}
