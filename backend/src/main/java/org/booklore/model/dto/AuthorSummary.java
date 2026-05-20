package org.booklore.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthorSummary {
    private Long id;
    private String name;
    private String asin;
    private int bookCount;
    private boolean hasPhoto;
}
