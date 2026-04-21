package org.booklore.nativelib;

import org.springframework.stereotype.Component;

/**
 * Spring-managed thin delegate over {@link NativeLibraries}.
 *
 * <p>This component does <b>no</b> initialization work of its own. Every query
 * is forwarded to the JVM-level singleton, whose {@link NativeLibraries#get()
 * holder} guarantees sequential, single-shot native-library loading under the
 * class-loader init lock.
 *
 * <p>Declaring {@code libs} as a field initializer (rather than deferring to
 * {@code @PostConstruct}) means the Spring bean-creation thread triggers
 * loading the first time the bean is built. Because the JVM class-init lock
 * is already serializing that load, no additional synchronization — and
 * specifically no {@code @PostConstruct} — is required.
 */
@Component
public class NativeLibraryManager {

    private final NativeLibraries libs = NativeLibraries.get();

    public boolean isPdfiumAvailable()     { return libs.isPdfiumAvailable(); }
    public boolean isLibArchiveAvailable() { return libs.isLibArchiveAvailable(); }
    public boolean isEpubNativeAvailable() { return libs.isEpubNativeAvailable(); }

    public boolean isAvailable(NativeLibraries.Library lib) {
        return libs.isAvailable(lib);
    }
}
