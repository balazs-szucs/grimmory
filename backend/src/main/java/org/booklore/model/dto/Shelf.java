package org.booklore.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.booklore.model.enums.IconType;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Shelf {
    private Long id;
    private String name;
    private String icon;
    private IconType iconType;
    private Sort sort;
    private Long userId;
    private boolean publicShelf;
    private int bookCount;
}
