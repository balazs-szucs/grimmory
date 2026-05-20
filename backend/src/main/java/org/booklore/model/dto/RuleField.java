package org.booklore.model.dto;

import com.dslplatform.json.JsonAttribute;
public enum RuleField {
    @JsonAttribute(name = "library")
    LIBRARY,
    @JsonAttribute(name = "shelf")
    SHELF,
    @JsonAttribute(name = "title")
    TITLE,
    @JsonAttribute(name = "subtitle")
    SUBTITLE,
    @JsonAttribute(name = "authors")
    AUTHORS,
    @JsonAttribute(name = "categories")
    CATEGORIES,
    @JsonAttribute(name = "publisher")
    PUBLISHER,
    @JsonAttribute(name = "publishedDate")
    PUBLISHED_DATE,
    @JsonAttribute(name = "seriesName")
    SERIES_NAME,
    @JsonAttribute(name = "seriesNumber")
    SERIES_NUMBER,
    @JsonAttribute(name = "seriesTotal")
    SERIES_TOTAL,
    @JsonAttribute(name = "pageCount")
    PAGE_COUNT,
    @JsonAttribute(name = "language")
    LANGUAGE,
    @JsonAttribute(name = "isbn13")
    ISBN13,
    @JsonAttribute(name = "isbn10")
    ISBN10,
    @JsonAttribute(name = "amazonRating")
    AMAZON_RATING,
    @JsonAttribute(name = "amazonReviewCount")
    AMAZON_REVIEW_COUNT,
    @JsonAttribute(name = "goodreadsRating")
    GOODREADS_RATING,
    @JsonAttribute(name = "goodreadsReviewCount")
    GOODREADS_REVIEW_COUNT,
    @JsonAttribute(name = "hardcoverRating")
    HARDCOVER_RATING,
    @JsonAttribute(name = "hardcoverReviewCount")
    HARDCOVER_REVIEW_COUNT,
    @JsonAttribute(name = "ranobedbRating")
    RANOBEDB_RATING,
    @JsonAttribute(name = "personalRating")
    PERSONAL_RATING,
    @JsonAttribute(name = "fileType")
    FILE_TYPE,
    @JsonAttribute(name = "fileSize")
    FILE_SIZE,
    @JsonAttribute(name = "readStatus")
    READ_STATUS,
    @JsonAttribute(name = "dateFinished")
    DATE_FINISHED,
    @JsonAttribute(name = "lastReadTime")
    LAST_READ_TIME,
    @JsonAttribute(name = "metadataScore")
    METADATA_SCORE,
    @JsonAttribute(name = "moods")
    MOODS,
    @JsonAttribute(name = "tags")
    TAGS,
    @JsonAttribute(name = "genre")
    GENRE,
    @JsonAttribute(name = "ageRating")
    AGE_RATING,
    @JsonAttribute(name = "contentRating")
    CONTENT_RATING,
    @JsonAttribute(name = "addedOn")
    ADDED_ON,
    @JsonAttribute(name = "lubimyczytacRating")
    LUBIMYCZYTAC_RATING,
    @JsonAttribute(name = "description")
    DESCRIPTION,
    @JsonAttribute(name = "narrator")
    NARRATOR,
    @JsonAttribute(name = "audibleRating")
    AUDIBLE_RATING,
    @JsonAttribute(name = "audibleReviewCount")
    AUDIBLE_REVIEW_COUNT,
    @JsonAttribute(name = "abridged")
    ABRIDGED,
    @JsonAttribute(name = "audiobookDuration")
    AUDIOBOOK_DURATION,
    @JsonAttribute(name = "audiobookCodec")
    AUDIOBOOK_CODEC,
    @JsonAttribute(name = "audiobookChapterCount")
    AUDIOBOOK_CHAPTER_COUNT,
    @JsonAttribute(name = "audiobookBitrate")
    AUDIOBOOK_BITRATE,
    @JsonAttribute(name = "isPhysical")
    IS_PHYSICAL,
    @JsonAttribute(name = "seriesStatus")
    SERIES_STATUS,
    @JsonAttribute(name = "seriesGaps")
    SERIES_GAPS,
    @JsonAttribute(name = "seriesPosition")
    SERIES_POSITION,
    @JsonAttribute(name = "readingProgress")
    READING_PROGRESS,
    @JsonAttribute(name = "metadataPresence")
    METADATA_PRESENCE
}

