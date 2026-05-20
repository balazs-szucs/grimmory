package org.booklore.model.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AuthorDetails {
    private Long id;
    private String name;
    private String description;
    private String asin;
    private boolean nameLocked;
    private boolean descriptionLocked;
    private boolean asinLocked;
    private boolean photoLocked;
}
