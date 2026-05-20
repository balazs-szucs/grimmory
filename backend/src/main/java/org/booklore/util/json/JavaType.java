package org.booklore.util.json;

import java.lang.reflect.Type;

public class JavaType implements Type {
    private final Type type;

    public JavaType(Type type) {
        this.type = type;
    }

    public Type getType() {
        return type;
    }
}
