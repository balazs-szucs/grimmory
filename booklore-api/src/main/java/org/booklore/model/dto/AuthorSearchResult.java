package org.booklore.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.booklore.model.enums.AuthorMetadataSource;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthorSearchResult {
    private AuthorMetadataSource source;
    private String asin;
    private String name;
    private String description;
    private String imageUrl;
}
