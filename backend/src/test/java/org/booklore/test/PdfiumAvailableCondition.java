package org.booklore.test;

import org.booklore.nativelib.NativeLibraries;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Enables tests only when PDFium native binaries are available on the current
 * platform.
 *
 * <p>Delegates to {@link NativeLibraries}, which is the single source of
 * truth for native-library availability across the JVM. Loading happens
 * exactly once, under the class-loader init lock, before the first test
 * runs (via {@link NativeLibraryExtension}). This class performs no loading
 * itself.
 */
public class PdfiumAvailableCondition implements ExecutionCondition {

    private static final ConditionEvaluationResult ENABLED =
            ConditionEvaluationResult.enabled("PDFium native library is available");

    private static final ConditionEvaluationResult DISABLED =
            ConditionEvaluationResult.disabled("PDFium native library not available on this platform");

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        return NativeLibraries.get().isPdfiumAvailable() ? ENABLED : DISABLED;
    }
}
