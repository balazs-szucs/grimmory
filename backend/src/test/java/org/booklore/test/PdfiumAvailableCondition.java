package org.booklore.test;

import org.grimmory.pdfium4j.PdfiumLibrary;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Enables tests only when PDFium native binaries are available on the current
 * platform.
 *
 * <p>Delegates to PDFium's own availability probe.
 */
public class PdfiumAvailableCondition implements ExecutionCondition {

    private static final ConditionEvaluationResult ENABLED =
            ConditionEvaluationResult.enabled("PDFium native library is available");

    private static final ConditionEvaluationResult DISABLED =
            ConditionEvaluationResult.disabled("PDFium native library not available on this platform");

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        return PdfiumLibrary.isAvailable() ? ENABLED : DISABLED;
    }
}
