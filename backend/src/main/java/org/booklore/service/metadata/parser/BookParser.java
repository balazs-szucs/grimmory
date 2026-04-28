package org.booklore.service.metadata.parser;

import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.FetchMetadataRequest;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public interface BookParser {

    List<BookMetadata> fetchMetadata(Book book, FetchMetadataRequest fetchMetadataRequest);

    default void fetchMetadata(Book book, FetchMetadataRequest request, Consumer<BookMetadata> consumer) {
        fetchMetadata(book, request, () -> false, consumer);
    }

    default void fetchMetadata(Book book, FetchMetadataRequest request, BooleanSupplier isCancelled, Consumer<BookMetadata> consumer) {
        List<BookMetadata> metadataList = fetchMetadata(book, request);
        if (metadataList != null) {
            for (BookMetadata metadata : metadataList) {
                if (isCancelled.getAsBoolean()) return;
                consumer.accept(metadata);
            }
        }
    }

    BookMetadata fetchTopMetadata(Book book, FetchMetadataRequest fetchMetadataRequest);
}
