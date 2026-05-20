package org.booklore.model.dto.response.comicvineapi;

import com.dslplatform.json.JsonAttribute;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.dslplatform.json.CompiledJson;

import java.util.List;

@Data
@NoArgsConstructor
@CompiledJson
public class Comic {

    private int id;

    @JsonAttribute(name = "api_detail_url")
    private String apiDetailUrl;

    @JsonAttribute(name = "cover_date")
    private String coverDate;

    @JsonAttribute(name = "store_date")
    private String storeDate;

    private String description;

    private String deck;

    private String name;

    private String aliases;

    @JsonAttribute(name = "issue_number")
    private String issueNumber;

    private Image image;

    private Volume volume;

    @JsonAttribute(name = "resource_type")
    private String resourceType;

    @JsonAttribute(name = "person_credits")
    private List<PersonCredit> personCredits;

    @JsonAttribute(name = "character_credits")
    private List<CharacterCredit> characterCredits;

    @JsonAttribute(name = "team_credits")
    private List<TeamCredit> teamCredits;

    @JsonAttribute(name = "story_arc_credits")
    private List<StoryArcCredit> storyArcCredits;

    @JsonAttribute(name = "location_credits")
    private List<LocationCredit> locationCredits;

    @JsonAttribute(name = "start_year")
    private String startYear;

    @JsonAttribute(name = "count_of_issues")
    private Integer countOfIssues;

    @JsonAttribute(name = "site_detail_url")
    private String siteDetailUrl;

    @JsonAttribute(name = "first_issue")
    private FirstLastIssue firstIssue;

    @JsonAttribute(name = "last_issue")
    private FirstLastIssue lastIssue;

    private Publisher publisher;

    @Data
    @NoArgsConstructor
    @CompiledJson
    public static class Publisher {
        private int id;
        private String name;
        @JsonAttribute(name = "api_detail_url")
        private String apiDetailUrl;
    }

    @Data
    @NoArgsConstructor
    @CompiledJson
    public static class Image {
        @JsonAttribute(name = "icon_url")
        private String iconUrl;

        @JsonAttribute(name = "medium_url")
        private String mediumUrl;

        @JsonAttribute(name = "screen_url")
        private String screenUrl;

        @JsonAttribute(name = "screen_large_url")
        private String screenLargeUrl;

        @JsonAttribute(name = "small_url")
        private String smallUrl;

        @JsonAttribute(name = "super_url")
        private String superUrl;

        @JsonAttribute(name = "thumb_url")
        private String thumbUrl;

        @JsonAttribute(name = "tiny_url")
        private String tinyUrl;

        @JsonAttribute(name = "original_url")
        private String originalUrl;

        @JsonAttribute(name = "image_tags")
        private String imageTags;
    }

    @Data
    @NoArgsConstructor
    @CompiledJson
    public static class Volume {
        private int id;
        private String name;

        @JsonAttribute(name = "api_detail_url")
        private String apiDetailUrl;

        @JsonAttribute(name = "site_detail_url")
        private String siteDetailUrl;

        @JsonAttribute(name = "start_year")
        private String startYear;

        @JsonAttribute(name = "count_of_issues")
        private Integer countOfIssues;
    }

    @Data
    @NoArgsConstructor
    @CompiledJson
    public static class PersonCredit {
        private long id;
        private String name;
        private String role;
    }

    @Data
    @NoArgsConstructor
    @CompiledJson
    public static class CharacterCredit {
        private long id;
        private String name;
        @JsonAttribute(name = "api_detail_url")
        private String apiDetailUrl;
    }

    @Data
    @NoArgsConstructor
    @CompiledJson
    public static class TeamCredit {
        private long id;
        private String name;
        @JsonAttribute(name = "api_detail_url")
        private String apiDetailUrl;
    }

    @Data
    @NoArgsConstructor
    @CompiledJson
    public static class StoryArcCredit {
        private long id;
        private String name;
        @JsonAttribute(name = "api_detail_url")
        private String apiDetailUrl;
    }

    @Data
    @NoArgsConstructor
    @CompiledJson
    public static class LocationCredit {
        private long id;
        private String name;
        @JsonAttribute(name = "api_detail_url")
        private String apiDetailUrl;
    }

    @Data
    @NoArgsConstructor
    @CompiledJson
    public static class FirstLastIssue {
        private int id;
        private String name;
        @JsonAttribute(name = "api_detail_url")
        private String apiDetailUrl;
        @JsonAttribute(name = "issue_number")
        private String issueNumber;
    }
}
