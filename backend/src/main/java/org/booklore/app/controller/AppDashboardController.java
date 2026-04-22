package org.booklore.app.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
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
        BookLoreUser.UserSettings.DashboardConfig config = user.getSettings().getDashboardConfig();

        if (config == null || config.getScrollers() == null) {
            return ResponseEntity.ok(new AppDashboardResponse(Map.of()));
        }

        Map<String, List<AppBookSummary>> scrollerData = new HashMap<>();

        for (BookLoreUser.UserSettings.ScrollerConfig scroller : config.getScrollers()) {
            if (scroller.getEnabled() == null || !scroller.getEnabled()) {
                continue;
            }

            List<AppBookSummary> books = switch (scroller.getType()) {
                case LAST_READ -> mobileBookService.getContinueReading(scroller.getMaxItems());
                case LAST_LISTENED -> mobileBookService.getContinueListening(scroller.getMaxItems());
                case RECENTLY_ADDED -> mobileBookService.getRecentlyAdded(scroller.getMaxItems());
                case RECENTLY_SCANNED -> mobileBookService.getRecentlyScanned(scroller.getMaxItems());
                case RANDOM -> mobileBookService.getRandomBooks(0, scroller.getMaxItems(), null).getContent();
                case MAGIC_SHELF -> {
                    if (scroller.getId() != null) {
                        try {
                            long shelfId = Long.parseLong(scroller.getId());
                            yield mobileBookService.getBooksByMagicShelf(shelfId, 0, scroller.getMaxItems()).getContent();
                        } catch (NumberFormatException e) {
                            yield List.of();
                        }
                    }
                    yield List.of();
                }
            };

            scrollerData.put(scroller.getId(), books);
        }

        return ResponseEntity.ok()
                .header("Cache-Control", "private, max-age=60")
                .body(new AppDashboardResponse(scrollerData));
    }
}
