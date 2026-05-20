package org.booklore.repository.projection;

import org.booklore.model.enums.BookFileType;
import java.time.Instant;

public interface UserBookFileProgressProjection {
    Long getBookId();
    BookFileType getBookFileType();
    String getPositionData();
    String getPositionHref();
    Float getContentSourceProgressPercent();
    Float getProgressPercent();
    String getTtsPositionCfi();
    Instant getLastReadTime();
}
