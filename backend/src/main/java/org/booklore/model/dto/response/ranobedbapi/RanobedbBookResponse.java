package org.booklore.model.dto.response.ranobedbapi;

import com.dslplatform.json.JsonAttribute;
import lombok.Data;
import com.dslplatform.json.CompiledJson;
import java.util.List;

@Data
@CompiledJson
public class RanobedbBookResponse {
    private Book book;

    @Data
    @CompiledJson
    public static class Book {
        private String description;
        private String lang;
        private Long id;
        private String romaji;
        
        @JsonAttribute(name = "description_ja")
        private String descriptionJa;
        
        private Boolean hidden;
        
        @JsonAttribute(name = "image_id")
        private Long imageId;
        
        private String olang;
        private Boolean locked;
        
        @JsonAttribute(name = "c_release_date")
        private Long cReleaseDate;
        
        private String title;
        
        @JsonAttribute(name = "title_orig")
        private String titleOrig;
        
        @JsonAttribute(name = "romaji_orig")
        private String romajiOrig;

        private Image image;
        private Rating rating;
        private List<TitleEntry> titles;
        private List<Edition> editions;
        private List<Release> releases;
        private List<Publisher> publishers;
        private Series series;
    }

    @Data
    @CompiledJson
    public static class Rating {
        private Double score;
        private Integer count;
    }

    @Data
    @CompiledJson
    public static class Image {
        private Long id;
        private String filename;
        private Integer height;
        private Boolean nsfw;
        private Boolean spoiler;
        private Integer width;
    }

    @Data
    @CompiledJson
    public static class TitleEntry {
        private String lang;
        private String romaji;
        @JsonAttribute(name = "book_id")
        private Long bookId;
        private Boolean official;
        private String title;
    }

    @Data
    @CompiledJson
    public static class Edition {
        @JsonAttribute(name = "book_id")
        private Long bookId;
        private String lang;
        private String title;
        private Long eid;
        private List<Staff> staff;
    }

    @Data
    @CompiledJson
    public static class Staff {
        private String note;
        @JsonAttribute(name = "role_type")
        private RoleType roleType;
        private String romaji;
        private String name;
        @JsonAttribute(name = "staff_id")
        private Long staffId;
        @JsonAttribute(name = "staff_alias_id")
        private Long staffAliasId;
    }

    @Data
    @CompiledJson
    public static class Release {
        private String lang;
        private Long id;
        private String romaji;
        private String description;
        private Boolean hidden;
        private Boolean locked;
        @JsonAttribute(name = "release_date")
        private Long releaseDate;
        private String title;
        private String website;
        private String amazon;
        private String bookwalker;
        private Format format;
        private String isbn13;
        private Integer pages;
        private String rakuten;
    }

    @Data
    @CompiledJson
    public static class Publisher {
        private String lang;
        private Long id;
        private String romaji;
        private String name;
        @JsonAttribute(name = "publisher_type")
        private PublisherType publisherType;
    }

    @Data
    @CompiledJson
    public static class Series {
        private List<SeriesBook> books;
        private List<Tag> tags;
        private String lang;
        private Long id;
        private String romaji;
        private String title;
        @JsonAttribute(name = "title_orig")
        private String titleOrig;
        @JsonAttribute(name = "romaji_orig")
        private String romajiOrig;
    }

    @Data
    @CompiledJson
    public static class SeriesBook {
        private Long id;
        private String lang;
        private String romaji;
        private String title;
        @JsonAttribute(name = "title_orig")
        private String titleOrig;
        @JsonAttribute(name = "romaji_orig")
        private String romajiOrig;
        private Image image;
    }

    @Data
    @CompiledJson
    public static class Tag {
        private Long id;
        private String name;
        private TagType ttype;
    }

    // --- Enums for strict typing ---

    public enum RoleType {
        @JsonAttribute(name = "editor") EDITOR,
        @JsonAttribute(name = "staff") STAFF,
        @JsonAttribute(name = "author") AUTHOR,
        @JsonAttribute(name = "artist") ARTIST,
        @JsonAttribute(name = "translator") TRANSLATOR,
        @JsonAttribute(name = "narrator") NARRATOR
    }

    public enum Format {
        @JsonAttribute(name = "digital") DIGITAL,
        @JsonAttribute(name = "print") PRINT,
        @JsonAttribute(name = "audio") AUDIO
    }

    public enum PublisherType {
        @JsonAttribute(name = "publisher") PUBLISHER,
        @JsonAttribute(name = "imprint") IMPRINT
    }

    public enum TagType {
        @JsonAttribute(name = "tag") TAG,
        @JsonAttribute(name = "content") CONTENT,
        @JsonAttribute(name = "demographic") DEMOGRAPHIC,
        @JsonAttribute(name = "genre") GENRE
    }
}
