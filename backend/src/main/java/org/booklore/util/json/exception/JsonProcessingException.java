package org.booklore.util.json.exception;

public class JsonProcessingException extends JsonException {
    public JsonProcessingException(String msg) {
        super(msg);
    }
    public JsonProcessingException(String msg, Throwable rootCause) {
        super(msg, rootCause);
    }
}
