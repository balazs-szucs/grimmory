package org.booklore.util.json;

public class JsonMapper extends ObjectMapper {
    public static class Builder {
        private final JsonMapper mapper = new JsonMapper();

        public JsonMapper build() {
            return mapper;
        }

        public Builder configure(SerializationFeature feature, boolean state) {
            mapper.configure(feature, state);
            return this;
        }

        public Builder findAndAddModules() {
            return this;
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
