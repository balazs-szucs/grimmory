package org.booklore.test;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

public class PdfiumAvailableCondition implements ExecutionCondition {

    private static final ConditionEvaluationResult ENABLED =
            ConditionEvaluationResult.enabled("PDFium native library is available");

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        if (NativeLibraryInitializer.isPdfiumAvailable()) {
            return ENABLED;
        }
        return ConditionEvaluationResult.disabled("PDFium native library not available on this platform");
    }
}
