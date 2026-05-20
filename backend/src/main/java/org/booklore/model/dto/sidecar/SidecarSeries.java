package org.booklore.model.dto.sidecar;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SidecarSeries {
    private String name;
    private Float number;
    private Integer total;
}
