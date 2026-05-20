package org.booklore.util.json;

import com.dslplatform.json.JsonConverter;
import com.dslplatform.json.JsonReader;
import com.dslplatform.json.JsonWriter;
import com.dslplatform.json.ObjectConverter;
import org.booklore.util.json.node.ObjectNode;
import java.io.IOException;

@JsonConverter(target = ObjectNode.class)
public class ObjectNodeConverter {
    public static final JsonReader.ReadObject<ObjectNode> JSON_READER = new JsonReader.ReadObject<ObjectNode>() {
        @Override
        public ObjectNode read(JsonReader reader) throws IOException {
            Object val = ObjectConverter.deserializeObject(reader);
            return new ObjectNode(val);
        }
    };

    public static final JsonWriter.WriteObject<ObjectNode> JSON_WRITER = new JsonWriter.WriteObject<ObjectNode>() {
        @Override
        public void write(JsonWriter writer, ObjectNode value) {
            if (value == null) {
                writer.writeNull();
            } else {
                writer.serializeObject(value.getValue());
            }
        }
    };
}
