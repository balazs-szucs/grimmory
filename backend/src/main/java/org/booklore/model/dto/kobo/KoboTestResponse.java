package org.booklore.model.dto.kobo;


import com.dslplatform.json.JsonAttribute;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KoboTestResponse {

    @JsonAttribute(name = "Result")
    private String result;

    @JsonAttribute(name = "TestKey")
    private String testKey;

    @JsonAttribute(name = "Tests")
    private Map<String, Object> tests;
}
