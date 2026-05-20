package org.booklore.config;

import com.dslplatform.json.DslJson;
import com.dslplatform.json.JsonAttribute;
import org.booklore.context.KomgaCleanContext;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class DslJsonHttpMessageConverter extends AbstractHttpMessageConverter<Object> {

    private final DslJson<Object> dslJson = DslJsonConfig.DSL_JSON;

    public DslJsonHttpMessageConverter() {
        super(MediaType.APPLICATION_JSON, new MediaType("application", "*+json", StandardCharsets.UTF_8));
    }

    @Override
    protected boolean supports(Class<?> clazz) {
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Object readInternal(Class<?> clazz, HttpInputMessage inputMessage) 
            throws IOException, HttpMessageNotReadableException {
        try (InputStream is = inputMessage.getBody()) {
            if (isUpperCamelCase(clazz)) {
                Map<String, Object> map = (Map<String, Object>) dslJson.deserialize(Map.class, is);
                return fromUpperCamelCaseMap(map, clazz);
            }
            return dslJson.deserialize(clazz, is);
        } catch (Exception e) {
            throw new HttpMessageNotReadableException("Could not read JSON: " + e.getMessage(), e, inputMessage);
        }
    }

    @Override
    protected void writeInternal(Object o, HttpOutputMessage outputMessage) 
            throws IOException, HttpMessageNotWritableException {
        try (OutputStream os = outputMessage.getBody()) {
            if (o == null) {
                os.write("null".getBytes(StandardCharsets.UTF_8));
                return;
            }
            
            if (KomgaCleanContext.isCleanMode()) {
                Object cleanObject = applyKomgaCleanFilter(o);
                dslJson.serialize(cleanObject, os);
                return;
            }

            Class<?> clazz = o.getClass();
            if (isUpperCamelCase(clazz)) {
                Object translated = toUpperCamelCaseValue(o);
                dslJson.serialize(translated, os);
                return;
            }

            dslJson.serialize(o, os);
        } catch (Exception e) {
            throw new HttpMessageNotWritableException("Could not write JSON: " + e.getMessage(), e);
        }
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

    private static Object toUpperCamelCaseValue(Object value) {
        if (value == null) return null;
        Class<?> valClass = value.getClass();
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
            return Enum.valueOf((Class<Enum>) targetType, val.toString());
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
}
