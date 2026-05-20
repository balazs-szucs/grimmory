package org.booklore.util.json;

import com.dslplatform.json.DslJson;
import com.dslplatform.json.JsonAttribute;
import org.booklore.util.json.exception.JsonException;
import org.booklore.util.json.exception.JsonProcessingException;
import org.booklore.util.json.type.TypeReference;
import org.booklore.util.json.node.ObjectNode;
import org.booklore.context.KomgaCleanContext;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

public class ObjectMapper {

    public static class TypeFactory {
        public static final TypeFactory instance = new TypeFactory();

        public JavaType constructCollectionType(Class<?> collectionClass, Class<?> elementClass) {
            return new JavaType(new ParameterizedType() {
                @Override
                public Type[] getActualTypeArguments() {
                    return new Type[]{elementClass};
                }
                @Override
                public Type getRawType() {
                    return collectionClass;
                }
                @Override
                public Type getOwnerType() {
                    return null;
                }
            });
        }
    }

    public TypeFactory getTypeFactory() {
        return TypeFactory.instance;
    }

    private final DslJson<Object> dslJson = org.booklore.config.DslJsonConfig.DSL_JSON;

    public ObjectMapper configure(SerializationFeature feature, boolean state) {
        return this;
    }

    @SuppressWarnings("unchecked")
    private static boolean isUpperCamelCase(Class<?> clazz) {
        if (clazz == null) return false;
        
        try {
            Class<?> j2NamingClass = Class.forName("com.fasterxml.jackson.databind.annotation.JsonNaming");
            Object j2Naming = clazz.getAnnotation((Class<java.lang.annotation.Annotation>) j2NamingClass);
            if (j2Naming != null) {
                java.lang.reflect.Method valueMethod = j2NamingClass.getMethod("value");
                Object strategyClass = valueMethod.invoke(j2Naming);
                if (strategyClass instanceof Class && ((Class<?>) strategyClass).getName().contains("UpperCamelCase")) {
                    return true;
                }
            }
        } catch (Exception e) {
        }

        try {
            Class<?> j3NamingClass = Class.forName("tools.jackson.databind.annotation.JsonNaming");
            Object j3Naming = clazz.getAnnotation((Class<java.lang.annotation.Annotation>) j3NamingClass);
            if (j3Naming != null) {
                java.lang.reflect.Method valueMethod = j3NamingClass.getMethod("value");
                Object strategyClass = valueMethod.invoke(j3Naming);
                if (strategyClass instanceof Class && ((Class<?>) strategyClass).getName().contains("UpperCamelCase")) {
                    return true;
                }
            }
        } catch (Exception e) {
        }

        return false;
    }

    @SuppressWarnings("unchecked")
    private static Object toUpperCamelCaseValue(Object value) {
        if (value == null) return null;
        Class<?> valClass = value.getClass();
        if (valClass.isEnum()) {
            Enum<?> e = (Enum<?>) value;
            try {
                java.lang.reflect.Field field = valClass.getField(e.name());
                if (field.isAnnotationPresent(JsonAttribute.class)) {
                    JsonAttribute prop = field.getAnnotation(JsonAttribute.class);
                    if (!prop.name().isEmpty()) {
                        return prop.name();
                    }
                }
                for (java.lang.annotation.Annotation ann : field.getAnnotations()) {
                    if (ann.annotationType().getName().contains("JsonProperty") || ann.annotationType().getName().contains("JsonAttribute")) {
                        try {
                            java.lang.reflect.Method valueMethod = ann.annotationType().getMethod("value");
                            String val = (String) valueMethod.invoke(ann);
                            if (val != null && !val.isEmpty()) {
                                return val;
                            }
                        } catch (Exception ex) {}
                        try {
                            java.lang.reflect.Method nameMethod = ann.annotationType().getMethod("name");
                            String val = (String) nameMethod.invoke(ann);
                            if (val != null && !val.isEmpty()) {
                                return val;
                            }
                        } catch (Exception ex) {}
                    }
                }
            } catch (Exception ex) {
            }
            return e.name();
        }
        if (isUpperCamelCase(valClass)) {
            return toUpperCamelCaseMap(value);
        }
        if (value instanceof List) {
            List<Object> newList = new ArrayList<>();
            for (Object item : (List<?>) value) {
                newList.add(toUpperCamelCaseValue(item));
            }
            return newList;
        }
        if (value instanceof Map) {
            Map<Object, Object> newMap = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                newMap.put(entry.getKey(), toUpperCamelCaseValue(entry.getValue()));
            }
            return newMap;
        }
        return value;
    }

    private static boolean isIgnoredField(java.lang.reflect.Field field) {
        if (field.isAnnotationPresent(JsonAttribute.class)) {
            return field.getAnnotation(JsonAttribute.class).ignore();
        }
        return false;
    }

    private static String getJsonPropertyName(java.lang.reflect.Field field) {
        if (field.isAnnotationPresent(JsonAttribute.class)) {
            JsonAttribute attr = field.getAnnotation(JsonAttribute.class);
            if (!attr.name().isEmpty()) {
                return attr.name();
            }
        }
        return field.getName();
    }

    private static Map<String, Object> toUpperCamelCaseMap(Object obj) {
        Map<String, Object> map = new LinkedHashMap<>();
        Class<?> clazz = obj.getClass();
        while (clazz != null && clazz != Object.class) {
            for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (java.lang.reflect.Modifier.isStatic(modifiers) || java.lang.reflect.Modifier.isTransient(modifiers)) {
                    continue;
                }
                if (isIgnoredField(field)) {
                    continue;
                }
                field.setAccessible(true);
                try {
                    Object val = field.get(obj);
                    String name = getJsonPropertyName(field);
                    if (name.equals(field.getName())) {
                        name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
                    }
                    map.put(name, toUpperCamelCaseValue(val));
                } catch (Exception e) {
                    // Ignore
                }
            }
            clazz = clazz.getSuperclass();
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Object fromUpperCamelCaseValue(Object jsonVal, Class<?> targetType, java.lang.reflect.Type genericType) {
        if (jsonVal == null) return null;
        if (isUpperCamelCase(targetType)) {
            if (jsonVal instanceof Map) {
                return fromUpperCamelCaseMap((Map<String, Object>) jsonVal, targetType);
            }
        }
        if (jsonVal instanceof List && List.class.isAssignableFrom(targetType)) {
            List<Object> newList = new ArrayList<>();
            java.lang.reflect.Type elementType = Object.class;
            if (genericType instanceof java.lang.reflect.ParameterizedType) {
                elementType = ((java.lang.reflect.ParameterizedType) genericType).getActualTypeArguments()[0];
            }
            Class<?> elementClass = (elementType instanceof Class) ? (Class<?>) elementType : Object.class;
            for (Object item : (List<?>) jsonVal) {
                newList.add(fromUpperCamelCaseValue(item, elementClass, elementType));
            }
            return newList;
        }
        if (jsonVal instanceof Map && Map.class.isAssignableFrom(targetType)) {
            Map<Object, Object> newMap = new LinkedHashMap<>();
            java.lang.reflect.Type keyType = Object.class;
            java.lang.reflect.Type valType = Object.class;
            if (genericType instanceof java.lang.reflect.ParameterizedType) {
                java.lang.reflect.Type[] args = ((java.lang.reflect.ParameterizedType) genericType).getActualTypeArguments();
                keyType = args[0];
                valType = args[1];
            }
            Class<?> keyClass = (keyType instanceof Class) ? (Class<?>) keyType : Object.class;
            Class<?> valClass = (valType instanceof Class) ? (Class<?>) valType : Object.class;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) jsonVal).entrySet()) {
                newMap.put(
                    fromUpperCamelCaseValue(entry.getKey(), keyClass, keyType),
                    fromUpperCamelCaseValue(entry.getValue(), valClass, valType)
                );
            }
            return newMap;
        }
        return coerceType(jsonVal, targetType);
    }

    @SuppressWarnings("unchecked")
    private static Object coerceType(Object val, Class<?> targetType) {
        if (val == null) return null;
        if (targetType.isInstance(val)) return val;
        if (targetType == double.class || targetType == Double.class) {
            return ((Number) val).doubleValue();
        }
        if (targetType == float.class || targetType == Float.class) {
            return ((Number) val).floatValue();
        }
        if (targetType == long.class || targetType == Long.class) {
            return ((Number) val).longValue();
        }
        if (targetType == int.class || targetType == Integer.class) {
            return ((Number) val).intValue();
        }
        if (targetType == boolean.class || targetType == Boolean.class) {
            if (val instanceof Boolean) return val;
            if (val instanceof String) return Boolean.parseBoolean((String) val);
            if (val instanceof Number) return ((Number) val).intValue() != 0;
        }
        if (targetType.isEnum()) {
            String str = val.toString();
            try {
                return Enum.valueOf((Class<Enum>) targetType, str);
            } catch (IllegalArgumentException e) {
            }
            try {
                return Enum.valueOf((Class<Enum>) targetType, str.toUpperCase());
            } catch (IllegalArgumentException e) {
            }
            for (Object enumConst : targetType.getEnumConstants()) {
                Enum<?> e = (Enum<?>) enumConst;
                try {
                    java.lang.reflect.Field field = targetType.getField(e.name());
                    if (field.isAnnotationPresent(JsonAttribute.class)) {
                        JsonAttribute prop = field.getAnnotation(JsonAttribute.class);
                        if (prop.name().equalsIgnoreCase(str)) {
                            return e;
                        }
                    }
                    for (java.lang.annotation.Annotation ann : field.getAnnotations()) {
                        if (ann.annotationType().getName().contains("JsonProperty") || ann.annotationType().getName().contains("JsonAttribute")) {
                            try {
                                java.lang.reflect.Method valueMethod = ann.annotationType().getMethod("value");
                                String propVal = (String) valueMethod.invoke(ann);
                                if (str.equalsIgnoreCase(propVal)) {
                                    return e;
                                }
                            } catch (Exception ex) {}
                            try {
                                java.lang.reflect.Method nameMethod = ann.annotationType().getMethod("name");
                                String propVal = (String) nameMethod.invoke(ann);
                                if (str.equalsIgnoreCase(propVal)) {
                                    return e;
                                }
                            } catch (Exception ex) {}
                        }
                    }
                } catch (Exception ex) {
                }
            }
            for (Object enumConst : targetType.getEnumConstants()) {
                Enum<?> e = (Enum<?>) enumConst;
                if (e.name().equalsIgnoreCase(str)) {
                    return e;
                }
            }
            return Enum.valueOf((Class<Enum>) targetType, str);
        }
        return val;
    }

    @SuppressWarnings("unchecked")
    private static <T> T fromUpperCamelCaseMap(Map<String, Object> map, Class<T> clazz) {
        try {
            T obj = clazz.getDeclaredConstructor().newInstance();
            Class<?> currentClass = clazz;
            while (currentClass != null && currentClass != Object.class) {
                for (java.lang.reflect.Field field : currentClass.getDeclaredFields()) {
                    int modifiers = field.getModifiers();
                    if (java.lang.reflect.Modifier.isStatic(modifiers) || java.lang.reflect.Modifier.isTransient(modifiers)) {
                        continue;
                    }
                    field.setAccessible(true);
                    String name = field.getName();
                    String customName = getJsonPropertyName(field);
                    if (!customName.equals(field.getName())) {
                        name = customName;
                    } else {
                        name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
                    }
                    if (map.containsKey(name)) {
                        Object val = map.get(name);
                        field.set(obj, fromUpperCamelCaseValue(val, field.getType(), field.getGenericType()));
                    }
                }
                currentClass = currentClass.getSuperclass();
            }
            return obj;
        } catch (Exception e) {
            throw new RuntimeException("Failed to populate DTO " + clazz.getName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object coerceNumbers(Object val) {
        if (val == null) return null;
        if (val instanceof Map) {
            Map<Object, Object> map = (Map<Object, Object>) val;
            Map<Object, Object> newMap = new LinkedHashMap<>();
            for (Map.Entry<Object, Object> entry : map.entrySet()) {
                newMap.put(entry.getKey(), coerceNumbers(entry.getValue()));
            }
            return newMap;
        }
        if (val instanceof List) {
            List<Object> list = (List<Object>) val;
            List<Object> newList = new ArrayList<>();
            for (Object item : list) {
                newList.add(coerceNumbers(item));
            }
            return newList;
        }
        if (val instanceof Double) {
            double d = (Double) val;
            if (d == (int) d) {
                return (int) d;
            }
            if (d == (long) d) {
                long l = (long) d;
                if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                    return (int) l;
                }
                return l;
            }
        }
        if (val instanceof Float) {
            float f = (Float) val;
            if (f == (int) f) {
                return (int) f;
            }
            if (f == (long) f) {
                long l = (long) f;
                if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                    return (int) l;
                }
                return l;
            }
        }
        if (val instanceof Long) {
            long l = (Long) val;
            if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                return (int) l;
            }
        }
        if (val instanceof java.math.BigDecimal) {
            java.math.BigDecimal bd = (java.math.BigDecimal) val;
            try {
                return bd.intValueExact();
            } catch (ArithmeticException e) {
                try {
                    long l = bd.longValueExact();
                    if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                        return (int) l;
                    }
                    return l;
                } catch (ArithmeticException e2) {
                    return bd.doubleValue();
                }
            }
        }
        return val;
    }

    @SuppressWarnings("unchecked")
    public <T> T readValue(byte[] bytes, Class<T> valueType) throws JsonException {
        if (bytes == null) return null;
        try {
            if (isUpperCamelCase(valueType)) {
                Map<String, Object> map = (Map<String, Object>) dslJson.deserialize(Map.class, bytes, bytes.length);
                return fromUpperCamelCaseMap(map, valueType);
            }
            Object result = dslJson.deserialize(valueType, bytes, bytes.length);
            return (T) coerceNumbers(result);
        } catch (IOException e) {
            throw new JsonException(e.getMessage(), e);
        }
    }

    public <T> T readValue(String content, Class<T> valueType) throws JsonException {
        if (content == null) return null;
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return readValue(bytes, valueType);
    }

    @SuppressWarnings("unchecked")
    public <T> T readValue(String content, JavaType valueType) throws JsonException {
        if (content == null) return null;
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try {
            Object result = dslJson.deserialize(valueType.getType(), bytes, bytes.length);
            return (T) coerceNumbers(result);
        } catch (IOException e) {
            throw new JsonException(e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T readValue(InputStream src, Class<T> valueType) throws JsonException {
        if (src == null) return null;
        try {
            if (isUpperCamelCase(valueType)) {
                Map<String, Object> map = (Map<String, Object>) dslJson.deserialize(Map.class, src);
                return fromUpperCamelCaseMap(map, valueType);
            }
            Object result = dslJson.deserialize(valueType, src);
            return (T) coerceNumbers(result);
        } catch (IOException e) {
            throw new JsonException(e.getMessage(), e);
        }
    }

    public <T> T readValue(Reader src, Class<T> valueType) throws JsonException {
        if (src == null) return null;
        try {
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[1024];
            int numRead;
            while ((numRead = src.read(buffer)) != -1) {
                sb.append(buffer, 0, numRead);
            }
            return readValue(sb.toString(), valueType);
        } catch (IOException e) {
            throw new JsonException(e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T readValue(byte[] bytes, TypeReference<T> valueTypeRef) throws JsonException {
        if (bytes == null) return null;
        try {
            Object result = dslJson.deserialize(valueTypeRef.getType(), bytes, bytes.length);
            return (T) coerceNumbers(result);
        } catch (IOException e) {
            throw new JsonException(e.getMessage(), e);
        }
    }

    public <T> T readValue(String content, TypeReference<T> valueTypeRef) throws JsonException {
        if (content == null) return null;
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return readValue(bytes, valueTypeRef);
    }

    @SuppressWarnings("unchecked")
    public <T> T readValue(InputStream src, TypeReference<T> valueTypeRef) throws JsonException {
        if (src == null) return null;
        try {
            Object result = dslJson.deserialize(valueTypeRef.getType(), src);
            return (T) coerceNumbers(result);
        } catch (IOException e) {
            throw new JsonException(e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object applyKomgaCleanFilter(Object obj) {
        if (obj == null) return null;
        if (obj instanceof String || obj instanceof Number || obj instanceof Boolean) {
            return obj;
        }
        if (obj instanceof Collection) {
            List<Object> cleanList = new ArrayList<>();
            for (Object item : (Collection<?>) obj) {
                Object cleanItem = applyKomgaCleanFilter(item);
                if (cleanItem != null) {
                    cleanList.add(cleanItem);
                }
            }
            return cleanList.isEmpty() ? null : cleanList;
        }
        if (obj instanceof Map) {
            Map<Object, Object> cleanMap = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) obj).entrySet()) {
                Object cleanKey = entry.getKey();
                Object cleanVal = applyKomgaCleanFilter(entry.getValue());
                if (cleanVal != null) {
                    cleanMap.put(cleanKey, cleanVal);
                }
            }
            return cleanMap.isEmpty() ? null : cleanMap;
        }

        Map<String, Object> map = new LinkedHashMap<>();
        Class<?> clazz = obj.getClass();
        while (clazz != null && clazz != Object.class) {
            for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (java.lang.reflect.Modifier.isStatic(modifiers) || java.lang.reflect.Modifier.isTransient(modifiers)) {
                    continue;
                }
                if (isIgnoredField(field)) {
                    continue;
                }
                String name = field.getName();
                if (name.endsWith("Lock")) {
                    continue;
                }
                field.setAccessible(true);
                try {
                    Object val = field.get(obj);
                    Object cleanVal = applyKomgaCleanFilter(val);
                    if (cleanVal != null) {
                        name = getJsonPropertyName(field);
                        map.put(name, cleanVal);
                    }
                } catch (Exception e) {
                    // Ignore
                }
            }
            clazz = clazz.getSuperclass();
        }
        return map.isEmpty() ? null : map;
    }

    public String writeValueAsString(Object value) throws JsonException {
        if (value == null) return "null";
        if (value instanceof JsonNode) {
            value = ((JsonNode) value).getValue();
        }
        if (KomgaCleanContext.isCleanMode()) {
            value = applyKomgaCleanFilter(value);
            if (value == null) return "null";
        }
        Class<?> valClass = value.getClass();
        if (isUpperCamelCase(valClass)) {
            value = toUpperCamelCaseValue(value);
        }
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            dslJson.serialize(value, baos);
            return baos.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new JsonException(e.getMessage(), e);
        }
    }

    public byte[] writeValueAsBytes(Object value) throws JsonException {
        if (value == null) return new byte[0];
        if (value instanceof JsonNode) {
            value = ((JsonNode) value).getValue();
        }
        if (KomgaCleanContext.isCleanMode()) {
            value = applyKomgaCleanFilter(value);
            if (value == null) return new byte[0];
        }
        Class<?> valClass = value.getClass();
        if (isUpperCamelCase(valClass)) {
            value = toUpperCamelCaseValue(value);
        }
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            dslJson.serialize(value, baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new JsonException(e.getMessage(), e);
        }
    }

    public JsonNode readTree(byte[] bytes) throws JsonException {
        if (bytes == null || bytes.length == 0) return JsonNode.missingNode();
        try {
            Object val = dslJson.deserialize(Object.class, bytes, bytes.length);
            return JsonNode.of(coerceNumbers(val));
        } catch (IOException e) {
            throw new JsonException(e.getMessage(), e);
        }
    }

    public JsonNode readTree(String content) throws JsonException {
        if (content == null || content.trim().isEmpty()) return JsonNode.missingNode();
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return readTree(bytes);
    }

    public JsonNode readTree(InputStream src) throws JsonException {
        if (src == null) return JsonNode.missingNode();
        try {
            Object val = dslJson.deserialize(Object.class, src);
            return JsonNode.of(coerceNumbers(val));
        } catch (IOException e) {
            throw new JsonException(e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T convertValue(Object fromValue, Class<T> toValueType) {
        if (fromValue == null) return null;
        try {
            if (toValueType.isInstance(fromValue)) {
                return toValueType.cast(fromValue);
            }
            if (fromValue instanceof JsonNode) {
                fromValue = ((JsonNode) fromValue).getValue();
            }
            if (isUpperCamelCase(toValueType)) {
                Map<String, Object> map;
                if (fromValue instanceof Map) {
                    map = (Map<String, Object>) fromValue;
                } else {
                    byte[] bytes = writeValueAsBytes(fromValue);
                    map = (Map<String, Object>) dslJson.deserialize(Map.class, bytes, bytes.length);
                }
                return fromUpperCamelCaseMap(map, toValueType);
            }
            byte[] bytes = writeValueAsBytes(fromValue);
            return readValue(bytes, toValueType);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to convert value: " + e.getMessage(), e);
        }
    }

    public <T> T convertValue(Object fromValue, TypeReference<T> toValueTypeRef) {
        if (fromValue == null) return null;
        try {
            if (fromValue instanceof JsonNode) {
                fromValue = ((JsonNode) fromValue).getValue();
            }
            byte[] bytes = writeValueAsBytes(fromValue);
            return readValue(bytes, toValueTypeRef);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to convert value: " + e.getMessage(), e);
        }
    }

    public <T> T treeToValue(JsonNode node, Class<T> toValueType) throws JsonException {
        if (node == null || node.isNull()) return null;
        return convertValue(node.getValue(), toValueType);
    }

    public ObjectNode createObjectNode() {
        return new ObjectNode();
    }

    public static class ObjectWriter {
        private final ObjectMapper mapper;

        public ObjectWriter(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        public String writeValueAsString(Object value) throws JsonException {
            return mapper.writeValueAsString(value);
        }
    }

    public ObjectWriter writerWithDefaultPrettyPrinter() {
        return new ObjectWriter(this);
    }
}
