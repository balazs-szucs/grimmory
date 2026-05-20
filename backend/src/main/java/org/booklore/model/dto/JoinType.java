package org.booklore.model.dto;

import com.dslplatform.json.JsonAttribute;
public enum JoinType {
    @JsonAttribute(name = "and")
    AND,
    @JsonAttribute(name = "or")
    OR
}

