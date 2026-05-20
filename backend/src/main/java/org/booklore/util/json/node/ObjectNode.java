package org.booklore.util.json.node;

import org.booklore.util.json.JsonNode;
import java.util.*;

public class ObjectNode extends JsonNode {
    public ObjectNode(Object value) {
        super(value);
    }

    public ObjectNode() {
        super(new LinkedHashMap<String, Object>());
    }

    public Collection<Map.Entry<String, JsonNode>> properties() {
        List<Map.Entry<String, JsonNode>> list = new ArrayList<>();
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String key = entry.getKey() == null ? "" : entry.getKey().toString();
                list.add(new AbstractMap.SimpleEntry<>(key, new JsonNode(entry.getValue())));
            }
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    public Iterable<String> propertyNames() {
        if (value instanceof Map) {
            return ((Map<String, ?>) value).keySet();
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    public void put(String fieldName, String value) {
        if (this.value instanceof Map) {
            ((Map<String, Object>) this.value).put(fieldName, value);
        }
    }

    @SuppressWarnings("unchecked")
    public void put(String fieldName, int value) {
        if (this.value instanceof Map) {
            ((Map<String, Object>) this.value).put(fieldName, value);
        }
    }

    @SuppressWarnings("unchecked")
    public void put(String fieldName, boolean value) {
        if (this.value instanceof Map) {
            ((Map<String, Object>) this.value).put(fieldName, value);
        }
    }

    @SuppressWarnings("unchecked")
    public void set(String fieldName, JsonNode value) {
        if (this.value instanceof Map) {
            ((Map<String, Object>) this.value).put(fieldName, value != null ? value.getValue() : null);
        }
    }
}
