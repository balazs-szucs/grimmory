package org.booklore.service;

import lombok.extern.slf4j.Slf4j;
import org.grimmory.pdfium4j.PdfDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;

/**
 * Serialises all PDFium native calls onto a single dedicated platform thread.
 *
 * <p>PDFium is fundamentally not thread-safe — even across different
 * documents.  This service extends {@link NativeExecutorService} which
 * handles bounded queuing, interrupt-safe blocking, timeouts, and
 * graceful shutdown.</p>
 */
@Slf4j
@Service
public final class PdfiumNativeService extends NativeExecutorService {

    @Autowired
    public PdfiumNativeService(NativeLibraryManager nativeLibraryManager) {
        super(nativeLibraryManager::isPdfiumAvailable, "PDFium", "pdfium-native-worker");
    }

    /** Test constructor — accepts a pre-built executor. */
    public PdfiumNativeService(ExecutorService executor, NativeLibraryManager nativeLibraryManager) {
        super(executor, nativeLibraryManager::isPdfiumAvailable, "PDFium");
    }

    /**
     * Open a {@link PdfDocument}, pass it to the given function, and
     * close it — all on the PDFium worker thread.
     */
    public <T> T withDocument(Path pdfPath, PdfDocumentFunction<T> operation) throws IOException {
        return execute(() -> {
            try (PdfDocument doc = PdfDocument.open(pdfPath)) {
                return operation.apply(doc);
            }
        });
    }

    /** Void variant of {@link #withDocument}. */
    public void withDocumentVoid(Path pdfPath, PdfDocumentConsumer operation) throws IOException {
        withDocument(pdfPath, doc -> { operation.accept(doc); return null; });
    }

    @FunctionalInterface
    public interface PdfDocumentFunction<T> {
        T apply(PdfDocument doc) throws Exception;
    }

    @FunctionalInterface
    public interface PdfDocumentConsumer {
        void accept(PdfDocument doc) throws Exception;
    }
}
