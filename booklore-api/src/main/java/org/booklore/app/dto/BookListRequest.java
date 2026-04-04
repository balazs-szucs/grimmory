package org.booklore.app.dto;

import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.ReadStatus;

public record BookListRequest(
        Integer page,
        Integer size,
        String sort,
        String dir,
        Long libraryId,
        Long shelfId,
        ReadStatus status,
        String search,
        BookFileType fileType,
        Integer minRating,
        Integer maxRating,
        String authors,
        String language,
        String series,
        String category,
        String publisher,
        String tag,
        String mood,
        String narrator,
        Long magicShelfId,
        Boolean unshelved) {
}
