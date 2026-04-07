package org.booklore.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
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
