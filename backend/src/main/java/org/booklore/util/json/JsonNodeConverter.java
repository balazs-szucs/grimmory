package org.booklore.util.json;

import com.dslplatform.json.JsonConverter;
import com.dslplatform.json.JsonReader;
import com.dslplatform.json.JsonWriter;
import com.dslplatform.json.ObjectConverter;
import java.io.IOException;

@JsonConverter(target = JsonNode.class)
public class JsonNodeConverter {
    public static final JsonReader.ReadObject<JsonNode> JSON_READER = new JsonReader.ReadObject<JsonNode>() {
        @Override
        public JsonNode read(JsonReader reader) throws IOException {
            Object val = ObjectConverter.deserializeObject(reader);
            return new JsonNode(val);
        }
    };

    public static final JsonWriter.WriteObject<JsonNode> JSON_WRITER = new JsonWriter.WriteObject<JsonNode>() {
        @Override
        public void write(JsonWriter writer, JsonNode value) {
            if (value == null) {
                writer.writeNull();
            } else {
                writer.serializeObject(value.getValue());
            }
        }
    };
}
