package org.booklore.model.dto;

import com.dslplatform.json.JsonAttribute;
import org.booklore.model.enums.OpdsSortOrder;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OpdsUserV2 {
    private Long id;
    private Long userId;
    private String username;
    @JsonAttribute(ignore = true)
    private String passwordHash;
    private OpdsSortOrder sortOrder;
}
