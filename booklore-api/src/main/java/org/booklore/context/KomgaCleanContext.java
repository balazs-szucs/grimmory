package org.booklore.context;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Request-scoped context to track whether the Komga API "clean" mode is enabled.
 * Uses request attributes instead of ThreadLocal for virtual-thread safety.
 * When clean mode is enabled:
 * - Fields ending with "Lock" are excluded from JSON serialization
 * - Null values are excluded from JSON serialization
 * - Metadata fields (language, summary, etc.) are allowed to be null
 */
public class KomgaCleanContext {
    private static final String CLEAN_MODE_ATTR = "komga.clean.mode";

    public static void setCleanMode(boolean enabled) {
        RequestContextHolder.currentRequestAttributes()
                .setAttribute(CLEAN_MODE_ATTR, enabled, RequestAttributes.SCOPE_REQUEST);
    }

    public static boolean isCleanMode() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs == null) return false;
        Boolean cleanMode = (Boolean) attrs.getAttribute(CLEAN_MODE_ATTR, RequestAttributes.SCOPE_REQUEST);
        return cleanMode != null && cleanMode;
    }

    public static void clear() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            attrs.removeAttribute(CLEAN_MODE_ATTR, RequestAttributes.SCOPE_REQUEST);
        }
    }
}
