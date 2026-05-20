package org.booklore.model.dto.settings;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetadataMatchWeights {
    @Builder.Default private int title = 10;
    @Builder.Default private int subtitle = 1;
    @Builder.Default private int description = 10;
    @Builder.Default private int authors = 10;
    @Builder.Default private int publisher = 5;
    @Builder.Default private int publishedDate = 3;
    @Builder.Default private int seriesName = 2;
    @Builder.Default private int seriesNumber = 2;
    @Builder.Default private int seriesTotal = 1;
    @Builder.Default private int isbn13 = 3;
    @Builder.Default private int isbn10 = 5;
    @Builder.Default private int language = 2;
    @Builder.Default private int pageCount = 1;
    @Builder.Default private int categories = 10;
    @Builder.Default private int amazonRating = 3;
    @Builder.Default private int amazonReviewCount = 2;
    @Builder.Default private int goodreadsRating = 4;
    @Builder.Default private int goodreadsReviewCount = 2;
    @Builder.Default private int hardcoverRating = 2;
    @Builder.Default private int hardcoverReviewCount = 1;
    @Builder.Default private int doubanRating = 3;
    @Builder.Default private int doubanReviewCount = 2;
    @Builder.Default private int ranobedbRating = 2;
    @Builder.Default private int lubimyczytacRating = 2;
    @Builder.Default private int audibleRating = 0;
    @Builder.Default private int audibleReviewCount = 0;
    @Builder.Default private int coverImage = 5;

    public int totalWeight() {
        return title + subtitle + description + authors + publisher + publishedDate +
                seriesName + seriesNumber + seriesTotal + isbn13 + isbn10 + language +
                pageCount + categories + amazonRating + amazonReviewCount +
                goodreadsRating + goodreadsReviewCount + hardcoverRating +
                hardcoverReviewCount + doubanRating + doubanReviewCount +
                ranobedbRating + lubimyczytacRating + audibleRating + audibleReviewCount + coverImage;
    }
}
