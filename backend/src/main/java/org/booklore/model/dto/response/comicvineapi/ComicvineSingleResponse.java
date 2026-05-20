package org.booklore.model.dto.response.comicvineapi;

import com.dslplatform.json.JsonAttribute;
import lombok.Data;
import com.dslplatform.json.CompiledJson;

@Data
@CompiledJson
public class ComicvineSingleResponse {
    private String error;
    private int limit;
    private int offset;
    @JsonAttribute(name = "number_of_page_results")
    private int numberOfPageResults;
    @JsonAttribute(name = "number_of_total_results")
    private int numberOfTotalResults;
    @JsonAttribute(name = "status_code")
    private int statusCode;
    private Comic results;
    private String version;
}
