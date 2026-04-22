package org.booklore.app.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.booklore.app.dto.AppBootstrapResponse;
import org.booklore.service.MenuCountsService;
import org.booklore.service.VersionService;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.user.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@AllArgsConstructor
@RestController
@RequestMapping("/api/v1/app/bootstrap")
@Tag(name = "App Bootstrap", description = "Consolidated initialization endpoints for the app")
public class AppBootstrapController {

    private final UserService userService;
    private final AppSettingService appSettingService;
    private final VersionService versionService;
    private final MenuCountsService menuCountsService;

    @Operation(
            summary = "Get bootstrap data",
            description = "Retrieve all data needed for application startup (user, settings, version, counts) in a single request.",
            operationId = "appGetBootstrap"
    )
    @GetMapping
    public ResponseEntity<AppBootstrapResponse> getBootstrap() {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .body(AppBootstrapResponse.builder()
                        .user(userService.getMyself())
                        .publicSettings(appSettingService.getPublicSettings())
                        .version(versionService.getVersionInfo())
                        .menuCounts(menuCountsService.getMenuCounts())
                        .build());
    }
}
