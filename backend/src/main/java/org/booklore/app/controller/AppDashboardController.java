package org.booklore.app.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.app.dto.AppBookSummary;
import org.booklore.app.dto.AppDashboardResponse;
import org.booklore.app.service.AppBookService;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.BookLoreUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@AllArgsConstructor
@RestController
@RequestMapping("/api/v1/app/dashboard")
@Tag(name = "App Dashboard", description = "Consolidated endpoints for the app dashboard experience")
@Slf4j
public class AppDashboardController {

    private final AppBookService mobileBookService;
    private final AuthenticationService authenticationService;

    @Operation(
            summary = "Get consolidated dashboard data",
            description = "Retrieve all data needed for the dashboard scrollers in a single request.",
            operationId = "appGetDashboard"
    )
    @GetMapping
    public ResponseEntity<AppDashboardResponse> getDashboard() {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        BookLoreUser.UserSettings.DashboardConfig config = user.getUserSettings().getDashboardConfig();

        if (config == null || config.getScrollers() == null) {
            return ResponseEntity.ok(new AppDashboardResponse(Map.of()));
        }

        Map<String, List<AppBookSummary>> scrollerData = new HashMap<>();

        for (BookLoreUser.UserSettings.ScrollerConfig scroller : config.getScrollers()) {
            if (!scroller.isEnabled()) {
                log.debug("[Dashboard] Scroller {} ({}) is disabled, skipping", scroller.getId(), scroller.getType());
                continue;
            }

            String type = scroller.getType();
            if (type == null) {
                log.warn("[Dashboard] Scroller {} has null type, skipping", scroller.getId());
                continue;
            }

            log.debug("[Dashboard] Fetching books for scroller {} (type: {}, max: {})", scroller.getId(), type, scroller.getMaxItems());
            List<AppBookSummary> books = switch (type) {
                case "lastRead", "LAST_READ" -> mobileBookService.getContinueReading(scroller.getMaxItems());
                case "lastListened", "LAST_LISTENED" -> mobileBookService.getContinueListening(scroller.getMaxItems());
                case "latestAdded", "LATEST_ADDED", "RECENTLY_ADDED" -> mobileBookService.getRecentlyAdded(scroller.getMaxItems());
                case "recentlyScanned", "RECENTLY_SCANNED" -> mobileBookService.getRecentlyScanned(scroller.getMaxItems());
                case "random", "RANDOM" -> mobileBookService.getRandomBooks(0, scroller.getMaxItems(), null).getContent();
                case "magicShelf", "MAGIC_SHELF" -> {
                    if (scroller.getMagicShelfId() != null) {
                        yield mobileBookService.getBooksByMagicShelf(scroller.getMagicShelfId(), 0, scroller.getMaxItems()).getContent();
                    }
                    log.warn("[Dashboard] Magic shelf scroller {} missing magicShelfId", scroller.getId());
                    yield List.of();
                }
                default -> {
                    log.warn("[Dashboard] Unknown scroller type: {}", type);
                    yield List.of();
                }
            };

            log.debug("[Dashboard] Scroller {} (type: {}) returned {} books", scroller.getId(), type, books.size());
            scrollerData.put(scroller.getId(), books);
        }

        return ResponseEntity.ok()
                .header("Cache-Control", "private, max-age=60")
                .body(new AppDashboardResponse(scrollerData));
    }
}
