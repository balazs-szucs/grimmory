package org.booklore.service.metadata.parser;

import org.booklore.model.dto.AuthorSearchResult;
import org.booklore.model.enums.AuthorMetadataSource;

import java.util.List;

public interface AuthorParser {

    AuthorMetadataSource getSource();

    List<AuthorSearchResult> searchAuthors(String name, String region);

    AuthorSearchResult getAuthorByAsin(String asin, String region);

    AuthorSearchResult quickSearch(String name, String region);
}
