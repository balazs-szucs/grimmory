package org.booklore.mapper;

import org.booklore.model.dto.BookMetadata;
import org.booklore.util.json.exception.JsonException;
import org.booklore.util.json.ObjectMapper;

public class JsonMetadataMapper {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static BookMetadata parse(String json) {
        try {
            return objectMapper.readValue(json, BookMetadata.class);
        } catch (JsonException e) {
            return null;
        }
    }

    public static String toJson(BookMetadata metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonException e) {
            return null;
        }
    }
}
