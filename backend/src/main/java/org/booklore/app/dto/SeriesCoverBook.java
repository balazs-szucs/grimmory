package org.booklore.app.dto;

import lombok.*;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeriesCoverBook {
    private Long bookId;
    private Instant coverUpdatedOn;
    private Float seriesNumber;
    private String primaryFileType;
}
