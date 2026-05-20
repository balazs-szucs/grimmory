package org.booklore.util.json;

import com.dslplatform.json.JsonAttribute;
import com.dslplatform.json.CompiledJson;
import org.booklore.util.json.exception.JsonException;
import org.booklore.util.json.node.ObjectNode;
import org.booklore.util.json.type.TypeReference;
import org.booklore.util.json.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ObjectMapperTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @CompiledJson
    public static class TestClass {
        private String name;
        private int value;

        @JsonAttribute(name = "special_field")
        private String specialField;

        @JsonAttribute(ignore = true)
        private String ignoredField;

        public TestClass() {}

        public TestClass(String name, int value, String specialField, String ignoredField) {
            this.name = name;
            this.value = value;
            this.specialField = specialField;
            this.ignoredField = ignoredField;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getValue() {
            return value;
        }

        public void setValue(int value) {
            this.value = value;
        }

        public String getSpecialField() {
            return specialField;
        }

        public void setSpecialField(String specialField) {
            this.specialField = specialField;
        }

        public String getIgnoredField() {
            return ignoredField;
        }

        public void setIgnoredField(String ignoredField) {
            this.ignoredField = ignoredField;
        }
    }

    @CompiledJson
    @tools.jackson.databind.annotation.JsonNaming(tools.jackson.databind.PropertyNamingStrategies.UpperCamelCaseStrategy.class)
    public static class CamelCaseClass {
        private String firstName;
        private String lastName;
        private int userAge;

        public CamelCaseClass() {}

        public CamelCaseClass(String firstName, String lastName, int userAge) {
            this.firstName = firstName;
            this.lastName = lastName;
            this.userAge = userAge;
        }

        public String getFirstName() {
            return firstName;
        }

        public void setFirstName(String firstName) {
            this.firstName = firstName;
        }

        public String getLastName() {
            return lastName;
        }

        public void setLastName(String lastName) {
            this.lastName = lastName;
        }

        public int getUserAge() {
            return userAge;
        }

        public void setUserAge(int userAge) {
            this.userAge = userAge;
        }
    }

    @Test
    @DisplayName("Test basic serialization to String")
    void testSerializeToString() throws Exception {
        TestClass obj = new TestClass("Booklore", 42, "Special Value", "Secret");

        String json = objectMapper.writeValueAsString(obj);

        assertTrue(json.contains("\"name\":\"Booklore\""));
        assertTrue(json.contains("\"value\":42"));
        assertTrue(json.contains("\"special_field\":\"Special Value\""));
        assertFalse(json.contains("ignoredField"));
        assertFalse(json.contains("Secret"));
    }

    @Test
    @DisplayName("Test basic deserialization from String")
    void testDeserializeFromString() throws Exception {
        String json = "{\"name\":\"Booklore\",\"value\":42,\"special_field\":\"Special Value\",\"ignoredField\":\"Secret\"}";
        TestClass obj = objectMapper.readValue(json, TestClass.class);

        assertNotNull(obj);
        assertEquals("Booklore", obj.getName());
        assertEquals(42, obj.getValue());
        assertEquals("Special Value", obj.getSpecialField());
        assertNull(obj.getIgnoredField()); // Ignored fields should not be deserialized
    }

    @Test
    @DisplayName("Test serialization to bytes")
    void testSerializeToBytes() throws Exception {
        TestClass obj = new TestClass("bytes-test", 100, "spec", "ignore");
        byte[] bytes = objectMapper.writeValueAsBytes(obj);

        assertNotNull(bytes);
        assertTrue(bytes.length > 0);

        TestClass deserialized = objectMapper.readValue(bytes, TestClass.class);
        assertEquals("bytes-test", deserialized.getName());
        assertEquals(100, deserialized.getValue());
    }

    @Test
    @DisplayName("Test deserialization from InputStream")
    void testDeserializeFromInputStream() throws Exception {
        String json = "{\"name\":\"stream-test\",\"value\":500}";
        ByteArrayInputStream is = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));

        TestClass obj = objectMapper.readValue(is, TestClass.class);
        assertNotNull(obj);
        assertEquals("stream-test", obj.getName());
        assertEquals(500, obj.getValue());
    }

    @Test
    @DisplayName("Test deserialization from Reader")
    void testDeserializeFromReader() throws Exception {
        String json = "{\"name\":\"reader-test\",\"value\":999}";
        StringReader reader = new StringReader(json);

        TestClass obj = objectMapper.readValue(reader, TestClass.class);
        assertNotNull(obj);
        assertEquals("reader-test", obj.getName());
        assertEquals(999, obj.getValue());
    }

    @Test
    @DisplayName("Test deserialization with TypeReference")
    void testDeserializeWithTypeReference() throws Exception {
        String json = "[{\"name\":\"one\",\"value\":1},{\"name\":\"two\",\"value\":2}]";
        List<TestClass> list = objectMapper.readValue(json, new TypeReference<List<TestClass>>() {});

        assertNotNull(list);
        assertEquals(2, list.size());
        assertEquals("one", list.get(0).getName());
        assertEquals("two", list.get(1).getName());
    }

    @Test
    @DisplayName("Test deserialization with custom JavaType")
    void testDeserializeWithJavaType() throws Exception {
        String json = "[{\"name\":\"one\",\"value\":1}]";
        JavaType type = objectMapper.getTypeFactory().constructCollectionType(List.class, TestClass.class);
        List<TestClass> list = objectMapper.readValue(json, type);

        assertNotNull(list);
        assertEquals(1, list.size());
        assertEquals("one", list.get(0).getName());
    }

    @Test
    @DisplayName("Test readTree and JsonNode manipulation")
    void testReadTreeAndJsonNode() throws Exception {
        String json = "{\"name\":\"tree-test\",\"nested\":{\"value\":123},\"list\":[1,2,3]}";
        JsonNode node = objectMapper.readTree(json);

        assertNotNull(node);
        assertTrue(node.isObject());
        assertEquals("tree-test", node.get("name").asText());
        assertEquals(123, node.get("nested").get("value").asInt());
        assertTrue(node.get("list").isArray());
        assertEquals(3, node.get("list").size());
    }

    @Test
    @DisplayName("Test value conversion with treeToValue")
    void testTreeToValue() throws Exception {
        String json = "{\"name\":\"tree-to-value-test\",\"value\":888}";
        JsonNode node = objectMapper.readTree(json);

        TestClass obj = objectMapper.treeToValue(node, TestClass.class);
        assertNotNull(obj);
        assertEquals("tree-to-value-test", obj.getName());
        assertEquals(888, obj.getValue());
    }

    @Test
    @DisplayName("Test convertValue between compatible types")
    void testConvertValue() throws Exception {
        Map<String, Object> map = Map.of("name", "conversion-test", "value", 777);
        TestClass obj = objectMapper.convertValue(map, TestClass.class);

        assertNotNull(obj);
        assertEquals("conversion-test", obj.getName());
        assertEquals(777, obj.getValue());
    }

    @Test
    @DisplayName("Test UpperCamelCase strategy naming conversions")
    void testUpperCamelCaseConversions() throws Exception {
        CamelCaseClass obj = new CamelCaseClass("John", "Doe", 30);
        String json = objectMapper.writeValueAsString(obj);

        // Serialization should casing convert fields to UpperCamelCase
        assertTrue(json.contains("\"FirstName\":\"John\""));
        assertTrue(json.contains("\"LastName\":\"Doe\""));
        assertTrue(json.contains("\"UserAge\":30"));

        // Deserialization should translate back
        CamelCaseClass deserialized = objectMapper.readValue(json, CamelCaseClass.class);
        assertNotNull(deserialized);
        assertEquals("John", deserialized.getFirstName());
        assertEquals("Doe", deserialized.getLastName());
        assertEquals(30, deserialized.getUserAge());
    }

    @Test
    @DisplayName("Test JsonException upon malformed JSON input")
    void testJsonExceptionOnMalformedJson() {
        String badJson = "{\"name\":";
        assertThrows(JsonException.class, () -> {
            objectMapper.readValue(badJson, TestClass.class);
        });
    }
}
