package org.booklore.model.dto.komga;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KomgaPageDto {
    private Integer number;
    private String fileName;
    private String mediaType;
    private Integer width;
    private Integer height;
    private Long fileSize;
}
