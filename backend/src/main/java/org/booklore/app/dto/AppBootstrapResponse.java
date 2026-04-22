package org.booklore.app.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.VersionInfo;
import org.booklore.model.dto.settings.PublicAppSetting;
import org.booklore.model.dto.response.MenuCountsResponse;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AppBootstrapResponse {
    private BookLoreUser user;
    private PublicAppSetting publicSettings;
    private VersionInfo version;
    private MenuCountsResponse menuCounts;
}
