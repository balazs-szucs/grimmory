package org.booklore.model.dto.komga;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KomgaMediaDto {
    private String status;
    private String mediaType;
    private String mediaProfile;
    private Integer pagesCount;
    private String comment;
    private Boolean epubDivinaCompatible;
    private Boolean epubIsKepub;
}
