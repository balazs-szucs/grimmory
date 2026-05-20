package org.booklore.app.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppAuthorDetail {
    private Long id;
    private String name;
    private String description;
    private String asin;
    private int bookCount;
    private boolean hasPhoto;
}
