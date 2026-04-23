package org.booklore.nativelib;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Spring-managed thin delegate over {@link NativeLibraries}.
 *
 * <p>Constructing this component eagerly triggers JVM-wide native loading
 * through the serialized holder path once per process.
 */
@Component
public class NativeLibraryManager {

    @PostConstruct
    void initializeAtStartup() {
        NativeLibraries.ensureInitialized();
    }
}
