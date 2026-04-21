package org.booklore.test;

import org.booklore.nativelib.NativeLibraries;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Global JUnit 5 extension that forces every native library to be loaded
 * before any test class begins executing — regardless of class ordering,
 * parallelism, or discovery order.
 *
 * <p>Registered automatically via {@code META-INF/services/
 * org.junit.jupiter.api.extension.Extension} plus
 * {@code junit.jupiter.extensions.autodetection.enabled = true} in
 * {@code junit-platform.properties}. No {@code @ExtendWith} annotations are
 * required on individual test classes.
 *
 * <p>Why this works:
 * <ol>
 *   <li>JUnit invokes {@link #beforeAll} on the first test class before any
 *       {@code @BeforeAll} / {@code @BeforeEach} / test method runs.</li>
 *   <li>{@link NativeLibraries#ensureInitialized()} triggers the
 *       {@code Holder} class initializer, which the JVM runs under the
 *       class-loader initialization lock.</li>
 *   <li>All subsequent invocations (from any thread, any test class) are
 *       lock-free no-ops that observe the already-initialized state.</li>
 * </ol>
 *
 * <p>The static helpers exist solely so tests can gate themselves with
 * {@code @EnabledIf("org.booklore.test.NativeLibraryExtension#...")}.
 */
public class NativeLibraryExtension implements BeforeAllCallback {

    @Override
    public void beforeAll(ExtensionContext context) {
        NativeLibraries.ensureInitialized();
    }

    // --- Static gates for @EnabledIf -----------------------------------

    public static boolean isPdfiumAvailable() {
        return NativeLibraries.get().isPdfiumAvailable();
    }

    public static boolean isLibArchiveAvailable() {
        return NativeLibraries.get().isLibArchiveAvailable();
    }

    public static boolean isEpubNativeAvailable() {
        return NativeLibraries.get().isEpubNativeAvailable();
    }
}
