package org.booklore.nativelib;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * JVM-wide single source of truth for native-library availability.
 *
 * <p>Initialization is intentionally serialized via the holder idiom so native
 * library loading is forced exactly once under the JVM class-init lock.
 *
 * <p><b>Important:</b> SIGSEGV is not catchable in Java. If a native library
 * crashes the process during load, the JVM exits immediately. This class only
 * handles Java-visible failures (e.g. UnsatisfiedLinkError). For hard process
 * isolation, probes must run in a subprocess.
 */
@Slf4j
public final class NativeLibraries {

    public enum Library {
        PDFIUM,
        LIBARCHIVE,
        EPUB4J_NATIVE
    }

    /**
     * Force reflective probes through the system loader to reduce duplicate
     * native-load paths across child class loaders in tests and bootstrappers.
     */
    private static final ClassLoader NATIVE_CL = ClassLoader.getSystemClassLoader();
    private static final String PROCESS_MARKER = "org.booklore.nativelibs.initialized";
    private static final String STATUS_PREFIX = "org.booklore.nativelibs.status.";
    private static final Map<Library, Probe> PROBES;

    static {
        Map<Library, Probe> probes = new LinkedHashMap<>();

        probes.put(Library.PDFIUM, new Probe("PDFium", () -> {
            Boolean clean = tryInvokeStaticBoolean("org.grimmory.pdfium4j.PdfiumLibrary");
            if (clean != null) {
                return clean;
            }
            org.grimmory.pdfium4j.PdfiumLibrary.initialize();
            return true;
        }));

        probes.put(Library.LIBARCHIVE, new Probe("libarchive", com.github.gotson.nightcompress.Archive::isAvailable));

        probes.put(Library.EPUB4J_NATIVE, new Probe("epub4j-native", () -> {
            Boolean clean = tryInvokeStaticBoolean(
                    "org.grimmory.epub4j.native_parsing.EpubNativeLibrary"
            );
            return clean != null && clean;
        }));

        PROBES = Collections.unmodifiableMap(probes);
    }

    private final Map<Library, Boolean> status;

    private NativeLibraries() {
        this.status = loadOrProbeProcessWideStatus();
        logSummary();
    }

    private Map<Library, Boolean> loadOrProbeProcessWideStatus() {
        Properties props = System.getProperties();
        synchronized (props) {
            if (Boolean.parseBoolean(props.getProperty(PROCESS_MARKER))) {
                return loadStatusFromProperties(props);
            }

            Map<Library, Boolean> result = new LinkedHashMap<>();
            for (Map.Entry<Library, Probe> entry : PROBES.entrySet()) {
                boolean available = runProbe(entry.getValue());
                result.put(entry.getKey(), available);
                props.setProperty(STATUS_PREFIX + entry.getKey().name(), Boolean.toString(available));
            }
            props.setProperty(PROCESS_MARKER, "true");
            return Collections.unmodifiableMap(result);
        }
    }

    private static Map<Library, Boolean> loadStatusFromProperties(Properties props) {
        Map<Library, Boolean> result = new LinkedHashMap<>();
        for (Library lib : Library.values()) {
            boolean available = Boolean.parseBoolean(props.getProperty(STATUS_PREFIX + lib.name(), "false"));
            result.put(lib, available);
        }
        return Collections.unmodifiableMap(result);
    }

    private static boolean runProbe(Probe probe) {
        try {
            return probe.fn.get();
        } catch (Throwable t) {
            log.warn("{} native library unavailable: {}", probe.name, t.toString());
            return false;
        }
    }

    private static Boolean tryInvokeStaticBoolean(String fqcn) throws Throwable {
        Class<?> cls;
        try {
            cls = Class.forName(fqcn, false, NATIVE_CL);
        } catch (ClassNotFoundException _) {
            return null;
        }

        Method method;
        try {
            method = cls.getMethod("isAvailable");
        } catch (NoSuchMethodException _) {
            return null;
        }

        if (method.getReturnType() != boolean.class) {
            return null;
        }

        // Same loader + class name returns the same Class object; this call only
        // forces static initialization before invoking the previously looked-up method.
        Class.forName(fqcn, true, NATIVE_CL);
        try {
            return (Boolean) method.invoke(null);
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            throw cause != null ? cause : ite;
        }
    }

    private void logSummary() {
        StringBuilder sb = new StringBuilder("Native libraries,");
        boolean first = true;
        for (Map.Entry<Library, Boolean> entry : status.entrySet()) {
            sb.append(first ? " " : ", ")
                    .append(PROBES.get(entry.getKey()).name)
                    .append(": ")
                    .append(entry.getValue() ? "loaded" : "NOT available");
            first = false;
        }
        log.info(sb.toString());
    }

    private static final class Holder {
        static final NativeLibraries INSTANCE = new NativeLibraries();
    }

    public static NativeLibraries get() {
        return Holder.INSTANCE;
    }

    public static void ensureInitialized() {
        get();
    }

    public boolean isAvailable(Library library) {
        return Boolean.TRUE.equals(status.get(library));
    }

    public boolean isPdfiumAvailable() {
        return isAvailable(Library.PDFIUM);
    }

    public boolean isLibArchiveAvailable() {
        return isAvailable(Library.LIBARCHIVE);
    }

    public boolean isEpubNativeAvailable() {
        return isAvailable(Library.EPUB4J_NATIVE);
    }

    private record Probe(String name, CheckedBooleanSupplier fn) {}

    @FunctionalInterface
    private interface CheckedBooleanSupplier {
        boolean get() throws Throwable;
    }
}
