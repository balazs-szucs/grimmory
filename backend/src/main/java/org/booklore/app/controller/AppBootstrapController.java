package org.booklore.app.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.app.dto.AppBootstrapResponse;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.service.MenuCountsService;
import org.booklore.service.ShelfService;
import org.booklore.service.VersionService;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.library.LibraryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@AllArgsConstructor
@RestController
@RequestMapping("/api/v1/app/bootstrap")
@Tag(name = "App Bootstrap", description = "Consolidated initialization endpoints for the app")
public class AppBootstrapController {

    private final AppSettingService appSettingService;
    private final VersionService versionService;
    private final MenuCountsService menuCountsService;
    private final LibraryService libraryService;
    private final ShelfService shelfService;
    private final AuthenticationService authenticationService;

    @Operation(
            summary = "Get bootstrap data",
            description = "Retrieve all data needed for application startup (user, settings, version, counts, libraries, shelves) in a single request.",
            operationId = "appGetBootstrap"
    )
    @GetMapping
    public ResponseEntity<AppBootstrapResponse> getBootstrap() {
        log.debug("[Bootstrap] Request received");
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        log.debug("[Bootstrap] Authenticated user: {}", user != null ? user.getUsername() : "anonymous");

        AppBootstrapResponse.AppBootstrapResponseBuilder builder = AppBootstrapResponse.builder()
                .publicSettings(appSettingService.getPublicSettings())
                .version(versionService.getVersionInfo());

        if (user != null && user.getId() != null && user.getId() != -1L) {
            try {
                log.debug("[Bootstrap] Fetching private data for user {}", user.getUsername());
                builder.user(user)
                        .menuCounts(menuCountsService.getMenuCounts())
                        .libraries(libraryService.getLibraries())
                        .shelves(shelfService.getShelves());
                log.debug("[Bootstrap] Private data fetched successfully");
            } catch (Exception e) {
                log.error("[Bootstrap] Failed to fetch complete bootstrap data for user {}: {}", user.getUsername(), e.getMessage(), e);
                // Proceed with partial data if possible
                builder.user(user);
            }
        }

        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .body(builder.build());
    }
}
