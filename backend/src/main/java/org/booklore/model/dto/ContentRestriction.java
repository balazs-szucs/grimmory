package org.booklore.model.dto;

import lombok.*;
import org.booklore.model.enums.ContentRestrictionMode;
import org.booklore.model.enums.ContentRestrictionType;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ContentRestriction {
    private Long id;
    private Long userId;
    private ContentRestrictionType restrictionType;
    private ContentRestrictionMode mode;
    private String value;
    private LocalDateTime createdAt;
}
