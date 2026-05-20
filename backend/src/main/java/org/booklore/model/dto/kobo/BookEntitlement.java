package org.booklore.model.dto.kobo;

import com.dslplatform.json.JsonAttribute;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
@Builder
public class BookEntitlement {
    private ActivePeriod activePeriod;

    @JsonAttribute(name = "IsRemoved")
    @Builder.Default
    private Boolean removed = false;

    private String status;

    @Builder.Default
    private String accessibility = "Full";

    private String crossRevisionId;
    private String revisionId;

    @JsonAttribute(name = "IsHiddenFromArchive")
    @Builder.Default
    private boolean hiddenFromArchive = false;

    private String id;
    private String created;
    private String lastModified;

    @JsonAttribute(name = "IsLocked")
    @Builder.Default
    private boolean locked = false;

    @Builder.Default
    private String originCategory = "Imported";

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
    @Builder
    public static class ActivePeriod {
        private String from;
        private String to;
    }
}
