package org.booklore.nativelib;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JVM-wide single source of truth for native-library availability.
 *
 * <p><b>Why this class exists.</b> The JVM specification (JLS §12.4.2) guarantees
 * that a class's static initializer runs <i>exactly once</i>, under the
 * class-loader initialization lock, and completes-happens-before any thread can
 * observe any member of that class. The {@link Holder} idiom leverages that
 * guarantee to serialize native-library loading without any explicit
 * synchronization, volatile fields, or double-checked-locking gymnastics.
 *
 * <p><b>Why sequential.</b> Loading is deliberately sequential. {@code dlopen()}
 * is thread-safe on modern glibc/musl, but:
 * <ul>
 *   <li>{@code JNI_OnLoad} and first-touch Panama-FFM symbol lookups walk
 *       shared loader state that is only serialized per-library, not
 *       cross-library.</li>
 *   <li>Some native libraries install atexit / signal handlers that race with
 *       each other when loaded concurrently.</li>
 *   <li>The startup-time saving from parallel loading is a few milliseconds.
 *       A SIGSEGV is forever.</li>
 * </ul>
 *
 * <p><b>Extending.</b> Adding a native library is one entry in {@link #PROBES}.
 * Every consumer (production code, tests, JUnit extensions) goes through
 * {@link #get()}, so a new library automatically inherits the same
 * once-and-serialized initialization contract.
 *
 * <p><b>Probe discipline.</b> A probe MUST perform whatever call is required to
 * actually force the native binary to load. Prefer a published {@code
 * isAvailable()} / {@code initialize()} entry point on the library itself, that
 * is the upstream API contract that every consumer should use. Fall back to a
 * {@code Class.forName(trueInit=true)} on the class whose static block performs
 * the {@code System.load} only when the upstream library lacks an explicit
 * init API (which is a library bug worth fixing upstream).
 */
@Slf4j
public final class NativeLibraries {

    /** Canonical identifier for each known native library. */
    public enum Library {
        PDFIUM,
        LIBARCHIVE,
        EPUB4J_NATIVE
    }

    /**
     * Registry of probes. Insertion order == load order.
     *
     * <p>Keep probes deterministic and side-effect-free beyond library loading.
     */
    private static final Map<Library, Probe> PROBES;

    static {
        Map<Library, Probe> m = new LinkedHashMap<>();

        m.put(Library.PDFIUM, new Probe("PDFium", () -> {
            Boolean clean = tryInvokeStaticBoolean("org.grimmory.pdfium4j.PdfiumLibrary", "isAvailable");
            if (clean != null) return clean;
            org.grimmory.pdfium4j.PdfiumLibrary.initialize();
            return true;
        }));

        // libarchive, nightcompress exposes isAvailable() directly on Archive.
        // This forces the multi-release impl class to initialize, which
        // triggers the underlying Panama load.
        m.put(Library.LIBARCHIVE, new Probe("libarchive", () -> com.github.gotson.nightcompress.Archive.isAvailable()));

        // epub4j-native, prefer EpubNativeLibrary.isAvailable() (introduced
        // in epub4j-native 1.2+). Falls back to forcing PanamaConstants to
        // initialize (its static block performs System.load).
        m.put(Library.EPUB4J_NATIVE, new Probe("epub4j-native", () -> {
            Boolean clean = tryInvokeStaticBoolean("org.grimmory.epub4j.native_parsing.EpubNativeLibrary", "isAvailable");
            if (clean != null) return clean;
            Class.forName(
                    "org.grimmory.epub4j.native_parsing.PanamaConstants",
                    true,
                    NativeLibraries.class.getClassLoader());
            return true;
        }));

        // Wrap as unmodifiable but keep LinkedHashMap iteration order,
        // Map.copyOf does not guarantee order, which would scramble the
        // summary log and the init sequence.
        PROBES = Collections.unmodifiableMap(m);
    }

    private final Map<Library, Boolean> status;

    private NativeLibraries() {
        Map<Library, Boolean> result = new LinkedHashMap<>();
        for (Map.Entry<Library, Probe> entry : PROBES.entrySet()) {
            result.put(entry.getKey(), runProbe(entry.getValue()));
        }
        this.status = Collections.unmodifiableMap(result);
        logSummary();
    }

    private static boolean runProbe(Probe p) {
        try {
            return p.fn.get();
        } catch (Throwable t) {
            // Throwable, not Exception, because UnsatisfiedLinkError is a
            // subclass of LinkageError, which is an Error.
            log.warn("{} native library unavailable: {}", p.name, t.toString());
            return false;
        }
    }

    /**
     * Reflectively invokes a static, no-arg {@code boolean} method on the named
     * class. Returns {@code null} if the class or method is absent, which lets
     * the caller fall back to a legacy probe path. Any {@link Throwable} from
     * the actual invocation propagates so the outer probe wrapper can log it.
     *
     * <p>The reflective path exists so this module is version-agnostic: it
     * prefers the clean non-throwing API when the consumer has upgraded, but
     * still works against older published jars that only expose
     * {@code initialize()}.
     */
    private static Boolean tryInvokeStaticBoolean(String fqcn, String methodName) throws Throwable {
        Class<?> cls;
        try {
            cls = Class.forName(fqcn, false, NativeLibraries.class.getClassLoader());
        } catch (ClassNotFoundException missing) {
            return null;
        }
        Method method;
        try {
            method = cls.getMethod(methodName);
        } catch (NoSuchMethodException missing) {
            return null;
        }
        if (method.getReturnType() != boolean.class) {
            return null;
        }
        // Force class init so the underlying native-library load happens here,
        // inside our serial init path, not on some later lazy touch.
        Class.forName(fqcn, true, NativeLibraries.class.getClassLoader());
        try {
            return (Boolean) method.invoke(null);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            throw cause != null ? cause : ite;
        }
    }

    private void logSummary() {
        StringBuilder sb = new StringBuilder("Native libraries,");
        boolean first = true;
        for (Map.Entry<Library, Boolean> e : status.entrySet()) {
            sb.append(first ? " " : ", ")
                    .append(PROBES.get(e.getKey()).name)
                    .append(": ")
                    .append(e.getValue() ? "loaded" : "NOT available");
            first = false;
        }
        log.info(sb.toString());
    }

    // ------------------------------------------------------------------
    // Holder, JVM runs this exactly once, under the class-init lock.
    // ------------------------------------------------------------------
    private static final class Holder {
        static final NativeLibraries INSTANCE = new NativeLibraries();
    }

    /**
     * Returns the singleton, forcing sequential initialization of every known
     * native library on first call. Safe to call from any thread at any time.
     */
    public static NativeLibraries get() {
        return Holder.INSTANCE;
    }

    /**
     * Triggers initialization if it hasn't happened yet. Cheap no-op otherwise.
     * Preferred spelling when the caller doesn't care about the instance.
     */
    public static void ensureInitialized() {
        NativeLibraries unused = Holder.INSTANCE;
    }

    public boolean isAvailable(Library lib) {
        return Boolean.TRUE.equals(status.get(lib));
    }

    public boolean isPdfiumAvailable()     { return isAvailable(Library.PDFIUM); }
    public boolean isLibArchiveAvailable() { return isAvailable(Library.LIBARCHIVE); }
    public boolean isEpubNativeAvailable() { return isAvailable(Library.EPUB4J_NATIVE); }

    // ------------------------------------------------------------------
    // Probe definition
    // ------------------------------------------------------------------
    private record Probe(String name, CheckedBooleanSupplier fn) {}

    @FunctionalInterface
    private interface CheckedBooleanSupplier {
        boolean get() throws Throwable;
    }
}
