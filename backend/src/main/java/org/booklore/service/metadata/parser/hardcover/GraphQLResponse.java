package org.booklore.service.metadata.parser.hardcover;

import com.dslplatform.json.JsonAttribute;
import lombok.*;
import com.dslplatform.json.CompiledJson;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@CompiledJson
public class GraphQLResponse {
    private Data data;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @CompiledJson
    public static class Data {
        private Search search;
        private List<BookWithEditions> books;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @CompiledJson
    public static class Search {
        private Results results;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @CompiledJson
    public static class Results {
        @JsonAttribute(name = "facet_counts")
        private List<Object> facetCounts;

        private Integer found;
        private List<Hit> hits;

        @JsonAttribute(name = "out_of")
        private Integer outOf;

        private Integer page;

        @JsonAttribute(name = "request_params")
        private Map<String, Object> requestParams;

        @JsonAttribute(name = "search_cutoff")
        private Boolean searchCutoff;

        @JsonAttribute(name = "search_time_ms")
        private Integer searchTimeMs;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @CompiledJson
    public static class Hit {
        private Document document;
        private Map<String, Object> highlight;
        private List<Map<String, Object>> highlights;

        @JsonAttribute(name = "text_match")
        private Long textMatch;

        @JsonAttribute(name = "text_match_info")
        private Map<String, Object> textMatchInfo;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @CompiledJson
    public static class Document {
        private String id;
        private String slug;
        private String title;
        private String subtitle;

        @JsonAttribute(name = "author_names")
        private Set<String> authorNames;

        private String description;
        private List<String> isbns;
        private Double rating;

        @JsonAttribute(name = "ratings_count")
        private Integer ratingsCount;

        @JsonAttribute(name = "reviews_count")
        private Integer reviewsCount;

        private Integer pages;

        @JsonAttribute(name = "release_date")
        private String releaseDate;

        @JsonAttribute(name = "release_year")
        private Integer releaseYear;

        private List<String> genres;
        private List<String> moods;
        private List<String> tags;

        @JsonAttribute(name = "featured_series")
        private FeaturedSeries featuredSeries;

        private Image image;

        @JsonAttribute(name = "alternative_titles")
        private List<String> alternativeTitles;

        @JsonAttribute(name = "activities_count")
        private Integer activitiesCount;

        private Boolean compilation;

        @JsonAttribute(name = "content_warnings")
        private List<String> contentWarnings;

        @JsonAttribute(name = "contribution_types")
        private List<String> contributionTypes;

        private List<Map<String, Object>> contributions;

        @JsonAttribute(name = "cover_color")
        private String coverColor;

        @JsonAttribute(name = "has_audiobook")
        private Boolean hasAudiobook;

        @JsonAttribute(name = "has_ebook")
        private Boolean hasEbook;

        @JsonAttribute(name = "lists_count")
        private Integer listsCount;

        @JsonAttribute(name = "prompts_count")
        private Integer promptsCount;

        @JsonAttribute(name = "series_names")
        private List<String> seriesNames;

        @JsonAttribute(name = "users_count")
        private Integer usersCount;

        @JsonAttribute(name = "users_read_count")
        private Integer usersReadCount;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @CompiledJson
    public static class BookWithEditions {
        private Integer id;
        private String slug;
        private String title;
        private String subtitle;
        private String description;

        @JsonAttribute(name = "cached_contributors")
        private List<Contributor> cachedContributors;

        @JsonAttribute(name = "featured_book_series")
        private FeaturedSeries featuredBookSeries;

        private Double rating;

        @JsonAttribute(name = "ratings_count")
        private Integer ratingsCount;

        @JsonAttribute(name = "reviews_count")
        private Integer reviewsCount;

        private Integer pages;

        @JsonAttribute(name = "release_date")
        private String releaseDate;

        @JsonAttribute(name = "release_year")
        private Integer releaseYear;

        private Image image;

        @JsonAttribute(name = "cached_tags")
        private CachedTags cachedTags;

        private List<Edition> editions;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @CompiledJson
    public static class Edition {
        private Integer id;
        private String title;
        private String subtitle;

        @JsonAttribute(name = "cached_contributors")
        private List<Contributor> cachedContributors;

        private Integer pages;

        @JsonAttribute(name = "release_date")
        private String releaseDate;

        @JsonAttribute(name = "release_year")
        private Integer releaseYear;

        private Image image;

        private Publisher publisher;

        @JsonAttribute(name = "isbn_10")
        private String isbn10;

        @JsonAttribute(name = "isbn_13")
        private String isbn13;

        private Language language;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @CompiledJson
    public static class Image {
        private String url;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @CompiledJson
    public static class FeaturedSeries {
        private Float position;
        private Series series;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @CompiledJson
    public static class Series {
        private String name;
        @JsonAttribute(name = "books_count")
        private Integer booksCount;
        @JsonAttribute(name = "primary_books_count")
        private Integer primaryBooksCount;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @CompiledJson
    public static class Contributor {
        private Author author;
        private String contribution;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @CompiledJson
    public static class Author {
        private Integer id;
        private String slug;
        private String name;
        private Image image;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @CompiledJson
    public static class CachedTags {
        @JsonAttribute(name = "Genre")
        private List<HardcoverCachedTag> genre;

        @JsonAttribute(name = "Mood")
        private List<HardcoverCachedTag> mood;

        @JsonAttribute(name = "Tag")
        private List<HardcoverCachedTag> tag;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @CompiledJson
    public static class Publisher {
        private String name;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @CompiledJson
    public static class Language {
        private String code2;
    }
}
