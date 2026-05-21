package org.booklore.repository.projection;

import org.booklore.model.enums.ReadStatus;
import java.time.Instant;

public interface UserBookProgressProjection {
    Long getBookId();
    ReadStatus getReadStatus();
    Instant getDateFinished();
    Integer getPersonalRating();
    Instant getLastReadTime();
    Float getKoboProgressPercent();
    Float getKoreaderProgressPercent();
    String getEpubProgress();
    String getEpubProgressHref();
    Float getEpubProgressPercent();
    Integer getPdfProgress();
    Float getPdfProgressPercent();
    Integer getCbxProgress();
    Float getCbxProgressPercent();
    String getKoreaderDevice();
    String getKoreaderDeviceId();
    Instant getKoreaderLastSyncTime();
}
