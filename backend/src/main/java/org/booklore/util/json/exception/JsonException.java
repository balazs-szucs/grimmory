package org.booklore.util.json.exception;

import java.io.IOException;

public class JsonException extends IOException {
    public JsonException(String msg) {
        super(msg);
    }
    public JsonException(String msg, Throwable rootCause) {
        super(msg, rootCause);
    }
}
