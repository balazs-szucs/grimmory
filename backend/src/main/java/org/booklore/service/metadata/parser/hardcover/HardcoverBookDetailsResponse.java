package org.booklore.service.metadata.parser.hardcover;

import com.dslplatform.json.JsonAttribute;
import lombok.*;
import com.dslplatform.json.CompiledJson;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@CompiledJson
public class HardcoverBookDetailsResponse {
    
    private Data data;
    
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @CompiledJson
    public static class Data {
        @JsonAttribute(name = "books_by_pk")
        private HardcoverBookDetails booksByPk;
    }
}
