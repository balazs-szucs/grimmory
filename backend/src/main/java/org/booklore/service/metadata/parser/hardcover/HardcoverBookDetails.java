package org.booklore.service.metadata.parser.hardcover;

import com.dslplatform.json.JsonAttribute;
import lombok.*;
import com.dslplatform.json.CompiledJson;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@CompiledJson
public class HardcoverBookDetails {
    
    private Integer id;
    private String title;
    
    @JsonAttribute(name = "cached_tags")
    private Map<String, List<HardcoverCachedTag>> cachedTags;
}
