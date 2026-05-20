package org.booklore.model.dto;

import com.dslplatform.json.JsonAttribute;
public enum RuleOperator {
    @JsonAttribute(name = "equals")
    EQUALS,
    @JsonAttribute(name = "not_equals")
    NOT_EQUALS,
    @JsonAttribute(name = "contains")
    CONTAINS,
    @JsonAttribute(name = "does_not_contain")
    DOES_NOT_CONTAIN,
    @JsonAttribute(name = "starts_with")
    STARTS_WITH,
    @JsonAttribute(name = "ends_with")
    ENDS_WITH,
    @JsonAttribute(name = "greater_than")
    GREATER_THAN,
    @JsonAttribute(name = "greater_than_equal_to")
    GREATER_THAN_EQUAL_TO,
    @JsonAttribute(name = "less_than")
    LESS_THAN,
    @JsonAttribute(name = "less_than_equal_to")
    LESS_THAN_EQUAL_TO,
    @JsonAttribute(name = "in_between")
    IN_BETWEEN,
    @JsonAttribute(name = "is_empty")
    IS_EMPTY,
    @JsonAttribute(name = "is_not_empty")
    IS_NOT_EMPTY,
    @JsonAttribute(name = "includes_any")
    INCLUDES_ANY,
    @JsonAttribute(name = "excludes_all")
    EXCLUDES_ALL,
    @JsonAttribute(name = "includes_all")
    INCLUDES_ALL,
    @JsonAttribute(name = "within_last")
    WITHIN_LAST,
    @JsonAttribute(name = "older_than")
    OLDER_THAN,
    @JsonAttribute(name = "this_period")
    THIS_PERIOD
}
