package org.booklore.model.dto.sidecar;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SidecarRatings {
    private SidecarRating amazon;
    private SidecarRating goodreads;
    private SidecarRating hardcover;
    private SidecarRating lubimyczytac;
    private SidecarRating ranobedb;
    private SidecarRating audible;
}
