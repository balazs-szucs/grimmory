package org.booklore.service.metadata;

public class BookLoreMetadata {
    // Legacy namespace values. Keep these as defaults for backward compatibility.
    public static final String NS_URI = "http://booklore.org/metadata/1.0/";
    public static final String NS_PREFIX = "booklore";

    // Alias prefix for rebranded metadata. Uses the same URI intentionally to avoid breaking readers.
    public static final String GRIMMORY_NS_PREFIX = "grimmory";
    public static final String GRIMMORY_NS_URI = NS_URI;
}
