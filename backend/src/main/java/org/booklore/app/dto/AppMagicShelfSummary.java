package org.booklore.app.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppMagicShelfSummary {
    private Long id;
    private String name;
    private String icon;
    private String iconType;
    private boolean publicShelf;
}
