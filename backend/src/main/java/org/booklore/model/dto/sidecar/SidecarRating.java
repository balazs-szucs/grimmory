package org.booklore.model.dto.sidecar;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SidecarRating {
    private Double average;
    private Integer count;
}
