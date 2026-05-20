package org.booklore.model.dto;


import com.dslplatform.json.JsonAttribute;
import lombok.Data;

@Data
public class OpdsUser {
    private Long id;
    private String username;
    @JsonAttribute(ignore = true)
    private String password;
}
