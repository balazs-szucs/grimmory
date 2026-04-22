package org.booklore.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.app.specification.AppBookSpecification;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.Library;
import org.booklore.model.dto.MagicShelf;
import org.booklore.model.dto.Shelf;
import org.booklore.model.dto.response.MenuCountsResponse;
import org.booklore.model.entity.BookEntity;
import org.booklore.repository.BookRepository;
import org.booklore.service.library.LibraryService;
import org.booklore.service.opds.MagicShelfBookService;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Produces lightweight aggregate book counts for the sidebar menu so the frontend
 * does not need to fetch the full book list on every app load just to render
 * counts next to libraries, shelves, and magic shelves.
 *
 * <p>Each count is a single {@code COUNT(*)} query against a scoping
 * {@link Specification}. Content restrictions are not applied (see
 * {@link MenuCountsResponse}) in exchange for bounded database work.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MenuCountsService {

    private final BookRepository bookRepository;
    private final AuthenticationService authenticationService;
    private final LibraryService libraryService;
    private final ShelfService shelfService;
    private final MagicShelfService magicShelfService;
    private final MagicShelfBookService magicShelfBookService;

    public MenuCountsResponse getMenuCounts() {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        if (user == null) {
            return new MenuCountsResponse(Map.of(), Map.of(), Map.of(), 0L, 0L);
        }

        Long userId = user.getId();
        Set<Long> accessibleLibraryIds = getAccessibleLibraryIds(user);

        Specification<BookEntity> visibleBooksSpec = AppBookSpecification.notDeleted();
        if (accessibleLibraryIds != null) {
            visibleBooksSpec = visibleBooksSpec.and(AppBookSpecification.inLibraries(accessibleLibraryIds));
        }

        Map<Long, Long> libraryCounts = computeLibraryCounts();
        Map<Long, Long> shelfCounts = computeShelfCounts(visibleBooksSpec);
        Map<Long, Long> magicShelfCounts = computeMagicShelfCounts(userId);

        long totalBookCount = bookRepository.count(visibleBooksSpec);
        long unshelvedBookCount = bookRepository.count(visibleBooksSpec.and(AppBookSpecification.unshelved()));

        return new MenuCountsResponse(libraryCounts, shelfCounts, magicShelfCounts, totalBookCount, unshelvedBookCount);
    }

    private Map<Long, Long> computeLibraryCounts() {
        Map<Long, Long> counts = new LinkedHashMap<>();
        for (Library library : libraryService.getLibraries()) {
            if (library == null || library.getId() == null) {
                continue;
            }
            long count = bookRepository.count(
                    AppBookSpecification.notDeleted().and(AppBookSpecification.inLibrary(library.getId()))
            );
            counts.put(library.getId(), count);
        }
        return counts;
    }

    private Map<Long, Long> computeShelfCounts(Specification<BookEntity> visibleBooksSpec) {
        Map<Long, Long> counts = new LinkedHashMap<>();
        for (Shelf shelf : shelfService.getShelves()) {
            if (shelf.getId() == null) {
                continue;
            }
            long count = bookRepository.count(
                    visibleBooksSpec.and(AppBookSpecification.inShelf(shelf.getId()))
            );
            counts.put(shelf.getId(), count);
        }
        return counts;
    }

    private Map<Long, Long> computeMagicShelfCounts(Long userId) {
        Map<Long, Long> counts = new LinkedHashMap<>();
        for (MagicShelf shelf : magicShelfService.getUserShelves()) {
            if (shelf.getId() == null) {
                continue;
            }
            try {
                Specification<BookEntity> spec = magicShelfBookService.toSpecification(userId, shelf.getId());
                counts.put(shelf.getId(), bookRepository.count(spec));
            } catch (Exception e) {
                log.warn("Failed to compute magic shelf count for shelf {}: {}", shelf.getId(), e.getMessage());
                counts.put(shelf.getId(), 0L);
            }
        }
        return counts;
    }

    private Set<Long> getAccessibleLibraryIds(BookLoreUser user) {
        if (user.getPermissions() != null && user.getPermissions().isAdmin()) {
            return null;
        }
        if (user.getAssignedLibraries() == null || user.getAssignedLibraries().isEmpty()) {
            return Collections.emptySet();
        }
        return user.getAssignedLibraries().stream()
                .map(Library::getId)
                .collect(Collectors.toSet());
    }
}
