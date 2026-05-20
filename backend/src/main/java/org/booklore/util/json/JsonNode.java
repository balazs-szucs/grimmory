package org.booklore.util.json;

import java.util.*;

public class JsonNode implements Iterable<JsonNode> {
    protected final Object value;
    protected final boolean missing;

    public JsonNode(Object value) {
        this.value = value;
        this.missing = false;
    }

    protected JsonNode(Object value, boolean missing) {
        this.value = value;
        this.missing = missing;
    }

    public static JsonNode of(Object value) {
        if (value == null) return new JsonNode(null);
        if (value instanceof Map) {
            return new org.booklore.util.json.node.ObjectNode(value);
        }
        return new JsonNode(value);
    }

    public static JsonNode missingNode() {
        return new JsonNode(null, true);
    }

    public Object getValue() {
        return value;
    }

    public boolean isMissingNode() {
        return missing;
    }

    public boolean isNull() {
        return !missing && value == null;
    }

    public boolean isObject() {
        return value instanceof Map;
    }

    public boolean isArray() {
        return value instanceof List;
    }

    public JsonNode get(String fieldName) {
        if (value instanceof Map) {
            Object val = ((Map<?, ?>) value).get(fieldName);
            return val == null ? null : of(val);
        }
        return null;
    }

    public JsonNode get(int index) {
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            if (index >= 0 && index < list.size()) {
                Object val = list.get(index);
                return val == null ? null : of(val);
            }
        }
        return null;
    }

    public JsonNode path(String fieldName) {
        if (missing) return this;
        JsonNode node = get(fieldName);
        return node != null ? node : missingNode();
    }

    public JsonNode path(int index) {
        if (missing) return this;
        JsonNode node = get(index);
        return node != null ? node : missingNode();
    }

    public String asText() {
        if (missing || value == null) return "";
        return value.toString();
    }

    public String asText(String defaultValue) {
        if (missing || value == null) return defaultValue;
        return value.toString();
    }

    public boolean isEmpty() {
        if (missing || value == null) return true;
        if (value instanceof Collection) {
            return ((Collection<?>) value).isEmpty();
        }
        if (value instanceof Map) {
            return ((Map<?, ?>) value).isEmpty();
        }
        return value.toString().isEmpty();
    }

    public boolean canConvertToInt() {
        if (missing || value == null) return false;
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            return d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE;
        }
        return false;
    }

    public boolean isIntegralNumber() {
        if (missing || value == null) return false;
        return value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte;
    }

    public int asInt() {
        if (missing || value == null) return 0;
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? 1 : 0;
        }
        return 0;
    }

    public boolean asBoolean() {
        if (missing || value == null) return false;
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        return false;
    }

    public boolean asBoolean(boolean defaultValue) {
        if (missing || value == null) return defaultValue;
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        return defaultValue;
    }

    public double asDouble() {
        if (missing || value == null) return 0.0;
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }
        return 0.0;
    }

    public long asLong() {
        if (missing || value == null) return 0L;
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
        return 0L;
    }

    public int size() {
        if (missing || value == null) return 0;
        if (value instanceof Collection) {
            return ((Collection<?>) value).size();
        }
        if (value instanceof Map) {
            return ((Map<?, ?>) value).size();
        }
        return 0;
    }

    public boolean has(String fieldName) {
        if (missing || value == null) return false;
        if (value instanceof Map) {
            return ((Map<?, ?>) value).containsKey(fieldName);
        }
        return false;
    }

    @Override
    public Iterator<JsonNode> iterator() {
        if (!missing && value instanceof List) {
            final Iterator<?> it = ((List<?>) value).iterator();
            return new Iterator<JsonNode>() {
                @Override
                public boolean hasNext() {
                    return it.hasNext();
                }
                @Override
                public JsonNode next() {
                    return of(it.next());
                }
            };
        }
        return Collections.emptyIterator();
    }

    public Iterator<Map.Entry<String, JsonNode>> fields() {
        if (!missing && value instanceof Map) {
            final Iterator<? extends Map.Entry<?, ?>> it = ((Map<?, ?>) value).entrySet().iterator();
            return new Iterator<Map.Entry<String, JsonNode>>() {
                @Override
                public boolean hasNext() {
                    return it.hasNext();
                }
                @Override
                public Map.Entry<String, JsonNode> next() {
                    Map.Entry<?, ?> entry = it.next();
                    String key = entry.getKey() == null ? "" : entry.getKey().toString();
                    return new AbstractMap.SimpleEntry<>(key, of(entry.getValue()));
                }
            };
        }
        return Collections.emptyIterator();
    }

    @Override
    public String toString() {
        if (missing) return "";
        if (value == null) return "null";
        return value.toString();
    }
}
