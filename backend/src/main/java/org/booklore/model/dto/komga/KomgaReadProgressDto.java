package org.booklore.model.dto.komga;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KomgaReadProgressDto {
    private Integer page;
    private Boolean completed;
    private Instant readDate;
    private Instant created;
    private Instant lastModified;
}
