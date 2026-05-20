package org.booklore.app.dto;

import lombok.*;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppSeriesSummary {
    private String seriesName;
    private int bookCount;
    private Integer seriesTotal;
    private List<String> authors;
    private int booksRead;
    private Instant latestAddedOn;
    private List<SeriesCoverBook> coverBooks;
}
