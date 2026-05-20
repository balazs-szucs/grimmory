package org.booklore.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupRule {
    private String name;
    private String type;
    private JoinType join;
    private List<Object> rules; // Can be either Rule or GroupRule
}
