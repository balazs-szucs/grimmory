package org.booklore.model.dto;

import java.time.Instant;
import java.time.LocalDate;

public record BookListItemDTO(
    Long id,
    String title,
    String subtitle,
    String seriesName,
    Double seriesNumber,
    String authors,      // comma-joined, done in SQL
    String tags,         // comma-joined, done in SQL
    String categories,   // comma-joined, done in SQL
    String bookCoverHash,
    Long libraryId,
    String libraryName,
    String publisher,
    LocalDate publishedDate,
    String language,
    Double rating,
    // read progress
    String readStatus,
    Instant lastReadTime,
    Double epubProgressPercent,
    Double pdfProgressPercent,
    Double cbxProgressPercent,
    Double koboProgressPercent,
    // file info
    String primaryBookType,
    Boolean isPhysical
) {}
