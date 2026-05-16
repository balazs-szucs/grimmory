package org.booklore.service.metadata.extractor;

import org.booklore.model.dto.BookMetadata;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

public interface FileMetadataExtractor {

    BookMetadata extractMetadata(File file);

    InputStream extractCover(File file) throws IOException;
}
