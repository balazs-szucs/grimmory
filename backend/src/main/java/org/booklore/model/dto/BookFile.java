package org.booklore.model.dto;

import lombok.Builder;
import lombok.Data;
import org.booklore.model.enums.BookFileType;
import org.booklore.util.ArchiveUtils;

import java.time.Instant;

import com.dslplatform.json.JsonAttribute;

@Builder
@Data
public class BookFile {
    private Long id;
    private Long bookId;
    private String fileName;
    private String filePath;
    private String fileSubPath;
    @JsonAttribute(name = "isBook")
    private boolean isBook;

    public boolean getIsBook() {
        return isBook;
    }

    public boolean isBook() {
        return isBook;
    }

    public void setIsBook(boolean isBook) {
        this.isBook = isBook;
    }
    private boolean folderBased;
    private BookFileType bookType;
    private ArchiveUtils.ArchiveType archiveType;
    private Long fileSizeKb;
    private String extension;
    private String description;
    private Instant addedOn;
}
