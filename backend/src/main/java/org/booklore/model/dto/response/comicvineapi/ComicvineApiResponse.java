package org.booklore.model.dto.response.comicvineapi;

import com.dslplatform.json.JsonAttribute;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.dslplatform.json.CompiledJson;

import java.util.List;

@Data
@NoArgsConstructor
@CompiledJson
public class ComicvineApiResponse {
    private String error;
    private int limit;
    private int offset;
    
    @JsonAttribute(name = "number_of_page_results")
    private int numberOfPageResults;
    
    @JsonAttribute(name = "number_of_total_results")
    private int numberOfTotalResults;
    
    @JsonAttribute(name = "status_code")
    private int statusCode;
    private List<Comic> results;
    private String version;
}
