package org.booklore.context;

/**
 * Context to track whether the Komga API "clean" mode is enabled.
 * Uses ScopedValue for efficient, immutable context sharing across threads.
 */
public class KomgaCleanContext {
    public static final ScopedValue<Boolean> CLEAN_MODE = ScopedValue.newInstance();

    private KomgaCleanContext() {}

    /**
     * Checks if the clean mode is enabled in the current scope.
     *
     * @return true if clean mode is enabled, false otherwise
     */
    public static boolean isCleanMode() {
        return CLEAN_MODE.isBound() && CLEAN_MODE.get();
    }
}
