package org.booklore.model.enums;

import com.dslplatform.json.JsonAttribute;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
public enum KoboReadStatus {
    @JsonAttribute(name = "ReadyToRead")
    READY_TO_READ,

    @JsonAttribute(name = "Finished")
    FINISHED,

    @JsonAttribute(name = "Reading")
    READING,
}
