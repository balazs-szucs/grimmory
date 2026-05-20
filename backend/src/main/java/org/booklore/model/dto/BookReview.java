package org.booklore.model.dto;

import org.booklore.model.enums.MetadataProvider;
import lombok.*;

import java.time.Instant;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BookReview {
    private Long id;
    private MetadataProvider metadataProvider;
    private String reviewerName;
    private String title;
    private Float rating;
    private Instant date;
    private String body;
    private String country;
    private Boolean spoiler;
    private Integer followersCount;
    private Integer textReviewsCount;
}
