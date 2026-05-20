package org.booklore.model.dto.response.audnexus;

import com.dslplatform.json.JsonAttribute;
import lombok.Data;
import com.dslplatform.json.CompiledJson;

import java.util.List;

@Data
@CompiledJson
public class AudnexusAuthorResponse {

    private String asin;
    private String name;
    private String description;

    @JsonAttribute(name = "image")
    private String imageUrl;
    private String region;
    private List<Genre> genres;
    private List<SimilarAuthor> similar;

    @Data
    public static class Genre {
        private String asin;
        private String name;
        private String type;
    }

    @Data
    public static class SimilarAuthor {
        private String asin;
        private String name;
    }
}
