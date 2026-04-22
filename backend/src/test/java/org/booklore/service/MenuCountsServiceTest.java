package org.booklore.service;

import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.BookLoreUser.UserPermissions;
import org.booklore.model.dto.Library;
import org.booklore.model.dto.MagicShelf;
import org.booklore.model.dto.Shelf;
import org.booklore.model.dto.response.MenuCountsResponse;
import org.booklore.model.entity.BookEntity;
import org.booklore.repository.BookRepository;
import org.booklore.service.library.LibraryService;
import org.booklore.service.opds.MagicShelfBookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MenuCountsServiceTest {

    @Mock private BookRepository bookRepository;
    @Mock private AuthenticationService authenticationService;
    @Mock private LibraryService libraryService;
    @Mock private ShelfService shelfService;
    @Mock private MagicShelfService magicShelfService;
    @Mock private MagicShelfBookService magicShelfBookService;

    private MenuCountsService service;

    @BeforeEach
    void setUp() {
        service = new MenuCountsService(
                bookRepository,
                authenticationService,
                libraryService,
                shelfService,
                magicShelfService,
                magicShelfBookService
        );
    }

    @Test
    void returnsEmptyMapsWhenNoAuthenticatedUser() {
        when(authenticationService.getAuthenticatedUser()).thenReturn(null);

        MenuCountsResponse response = service.getMenuCounts();

        assertThat(response.libraryCounts()).isEmpty();
        assertThat(response.shelfCounts()).isEmpty();
        assertThat(response.magicShelfCounts()).isEmpty();
    }

    @Test
    void countsAllLibrariesShelvesAndMagicShelvesForAdmin() {
        BookLoreUser admin = buildUser(42L, true, List.of(library(1L), library(2L)));
        when(authenticationService.getAuthenticatedUser()).thenReturn(admin);

        when(libraryService.getLibraries()).thenReturn(List.of(library(1L), library(2L)));
        when(shelfService.getShelves()).thenReturn(List.of(shelf(10L), shelf(11L)));
        when(magicShelfService.getUserShelves()).thenReturn(List.of(magicShelf(20L), magicShelf(21L)));

        when(bookRepository.count(any(Specification.class)))
                .thenReturn(100L, 50L, 7L, 3L, 9L, 2L, 150L, 40L);

        @SuppressWarnings("unchecked")
        Specification<BookEntity> magicSpec1 = mock(Specification.class);
        @SuppressWarnings("unchecked")
        Specification<BookEntity> magicSpec2 = mock(Specification.class);
        when(magicShelfBookService.toSpecification(42L, 20L)).thenReturn(magicSpec1);
        when(magicShelfBookService.toSpecification(42L, 21L)).thenReturn(magicSpec2);

        MenuCountsResponse response = service.getMenuCounts();

        assertThat(response.libraryCounts()).containsEntry(1L, 100L).containsEntry(2L, 50L);
        assertThat(response.shelfCounts()).containsEntry(10L, 7L).containsEntry(11L, 3L);
        assertThat(response.magicShelfCounts()).containsEntry(20L, 9L).containsEntry(21L, 2L);
        assertThat(response.totalBookCount()).isEqualTo(150L);
        assertThat(response.unshelvedBookCount()).isEqualTo(40L);
    }

    @Test
    void magicShelfCountFallsBackToZeroOnEvaluatorFailure() {
        BookLoreUser user = buildUser(7L, true, List.of());
        when(authenticationService.getAuthenticatedUser()).thenReturn(user);

        when(libraryService.getLibraries()).thenReturn(List.of());
        when(shelfService.getShelves()).thenReturn(List.of());
        when(magicShelfService.getUserShelves()).thenReturn(List.of(magicShelf(99L)));
        when(magicShelfBookService.toSpecification(7L, 99L))
                .thenThrow(new RuntimeException("broken rule"));

        when(bookRepository.count(any(Specification.class))).thenReturn(0L, 0L);

        MenuCountsResponse response = service.getMenuCounts();

        assertThat(response.magicShelfCounts()).containsEntry(99L, 0L);
    }

    @Test
    void nonAdminOnlyCountsAgainstAssignedLibraryScope() {
        BookLoreUser user = buildUser(5L, false, List.of(library(1L)));
        when(authenticationService.getAuthenticatedUser()).thenReturn(user);

        when(libraryService.getLibraries()).thenReturn(List.of(library(1L)));
        when(shelfService.getShelves()).thenReturn(List.of(shelf(10L)));
        when(magicShelfService.getUserShelves()).thenReturn(List.of());

        when(bookRepository.count(any(Specification.class))).thenReturn(25L, 4L, 25L, 12L);

        MenuCountsResponse response = service.getMenuCounts();

        assertThat(response.libraryCounts()).containsEntry(1L, 25L);
        assertThat(response.shelfCounts()).containsEntry(10L, 4L);
        assertThat(response.totalBookCount()).isEqualTo(25L);
        assertThat(response.unshelvedBookCount()).isEqualTo(12L);
        verify(magicShelfBookService, org.mockito.Mockito.never())
                .toSpecification(eq(5L), any());
    }

    private BookLoreUser buildUser(Long id, boolean isAdmin, List<Library> libraries) {
        UserPermissions permissions = new UserPermissions();
        permissions.setAdmin(isAdmin);
        BookLoreUser user = new BookLoreUser();
        user.setId(id);
        user.setPermissions(permissions);
        user.setAssignedLibraries(libraries);
        return user;
    }

    private Library library(Long id) {
        Library library = new Library();
        library.setId(id);
        library.setName("Library " + id);
        return library;
    }

    private Shelf shelf(Long id) {
        Shelf shelf = new Shelf();
        shelf.setId(id);
        shelf.setName("Shelf " + id);
        return shelf;
    }

    private MagicShelf magicShelf(Long id) {
        MagicShelf shelf = new MagicShelf();
        shelf.setId(id);
        shelf.setName("Magic " + id);
        return shelf;
    }
}
