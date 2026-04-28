package org.booklore.service.metadata;

import org.booklore.model.dto.CoverImage;
import org.booklore.model.dto.request.CoverFetchRequest;

import java.util.function.Consumer;

public interface BookCoverProvider {
    void getCovers(CoverFetchRequest request, Consumer<CoverImage> consumer);
}

