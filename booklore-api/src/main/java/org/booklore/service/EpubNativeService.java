package org.booklore.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;

/**
 * Serialises all epub4j-native (gumbo / pugixml) calls onto a single
 * dedicated platform thread.
 *
 * <p>Same rationale as {@link PdfiumNativeService}: the native code is
 * not thread-safe, so we extend {@link NativeExecutorService} which
 * handles bounded queuing, interrupt-safe blocking, timeouts, and
 * graceful shutdown.</p>
 */
@Slf4j
@Service
public final class EpubNativeService extends NativeExecutorService {

    @Autowired
    public EpubNativeService(NativeLibraryManager nativeLibraryManager) {
        super(nativeLibraryManager::isEpubNativeAvailable, "epub4j-native", "epub-native-worker");
    }

    /** Test constructor — accepts a pre-built executor. */
    public EpubNativeService(ExecutorService executor, NativeLibraryManager nativeLibraryManager) {
        super(executor, nativeLibraryManager::isEpubNativeAvailable, "epub4j-native");
    }
}
