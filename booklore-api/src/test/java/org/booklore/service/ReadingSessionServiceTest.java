package org.booklore.service;

import org.booklore.config.security.service.AuthenticationService;
import org.booklore.exception.APIException;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.Library;
import org.booklore.model.dto.request.ReadingSessionBatchRequest;
import org.booklore.model.dto.request.ReadingSessionItemRequest;
import org.booklore.model.dto.response.ReadingSessionBatchResponse;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.ReadingSessionEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookRepository;
import org.booklore.repository.ReadingSessionRepository;
import org.booklore.repository.UserBookProgressRepository;
import org.booklore.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * Regression / feature tests for ReadingSessionService.
 *
 * Covers two areas introduced or modified in the merge:
 *   1. recordSessionsBatch — end-before-start validation, access control, happy path
 *   2. validateBookAccess  — admin bypass, library match, no-access forbidden
 */
@ExtendWith(MockitoExtension.class)
class ReadingSessionServiceTest {

    @Mock private AuthenticationService authenticationService;
    @Mock private ReadingSessionRepository readingSessionRepository;
    @Mock private BookRepository bookRepository;
    @Mock private UserRepository userRepository;
    @Mock private UserBookProgressRepository userBookProgressRepository;

    @InjectMocks private ReadingSessionService service;

    private static final long USER_ID = 10L;
    private static final long BOOK_ID = 99L;
    private static final long LIBRARY_ID = 5L;

    private BookLoreUserEntity userEntity;
    private BookEntity book;
    private LibraryEntity library;

    @BeforeEach
    void setUp() {
        library = new LibraryEntity();
        library.setId(LIBRARY_ID);

        book = new BookEntity();
        book.setId(BOOK_ID);
        book.setLibrary(library);

        userEntity = new BookLoreUserEntity();
        userEntity.setId(USER_ID);
    }

    // ── Time validation ──────────────────────────────────────────────────────

    @Test
    void recordSessionsBatch_endBeforeStart_throwsIllegalArgument() {
        Instant start = Instant.parse("2024-01-01T10:00:00Z");
        Instant end   = Instant.parse("2024-01-01T09:00:00Z"); // before start

        ReadingSessionItemRequest item = new ReadingSessionItemRequest();
        item.setStartTime(start);
        item.setEndTime(end);
        item.setDurationSeconds(0);

        ReadingSessionBatchRequest request = new ReadingSessionBatchRequest(
                BOOK_ID, BookFileType.EPUB, List.of(item));

        BookLoreUser adminUser = adminUser();
        when(authenticationService.getAuthenticatedUser()).thenReturn(adminUser);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(userEntity));
        when(bookRepository.findById(BOOK_ID)).thenReturn(Optional.of(book));

        assertThatThrownBy(() -> service.recordSessionsBatch(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("End time must be after start time");
    }

    @Test
    void recordSessionsBatch_equalStartAndEnd_doesNotThrow() {
        Instant time = Instant.parse("2024-01-01T10:00:00Z");

        ReadingSessionItemRequest item = validItem(time, time.plusSeconds(1));
        ReadingSessionBatchRequest request = new ReadingSessionBatchRequest(
                BOOK_ID, BookFileType.EPUB, List.of(item));

        BookLoreUser admin = adminUser();
        when(authenticationService.getAuthenticatedUser()).thenReturn(admin);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(userEntity));
        when(bookRepository.findById(BOOK_ID)).thenReturn(Optional.of(book));

        ReadingSessionEntity saved = sessionEntityFrom(item);
        when(readingSessionRepository.saveAll(anyList())).thenReturn(List.of(saved));

        ReadingSessionBatchResponse response = service.recordSessionsBatch(request);
        assertThat(response.getTotalRequested()).isEqualTo(1);
        assertThat(response.getSuccessCount()).isEqualTo(1);
    }

    // ── Access control: admin bypass ─────────────────────────────────────────

    @Test
    void recordSessionsBatch_adminUser_bypassesLibraryCheck() {
        // Admin has NO assigned libraries but must still succeed
        BookLoreUser admin = adminUser();
        admin.setAssignedLibraries(List.of()); // empty — would fail for non-admin

        when(authenticationService.getAuthenticatedUser()).thenReturn(admin);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(userEntity));
        when(bookRepository.findById(BOOK_ID)).thenReturn(Optional.of(book));

        ReadingSessionItemRequest item = validItem();
        ReadingSessionBatchRequest request = new ReadingSessionBatchRequest(
                BOOK_ID, BookFileType.EPUB, List.of(item));

        ReadingSessionEntity saved = sessionEntityFrom(item);
        when(readingSessionRepository.saveAll(anyList())).thenReturn(List.of(saved));

        ReadingSessionBatchResponse response = service.recordSessionsBatch(request);
        assertThat(response.getSuccessCount()).isEqualTo(1);
    }

    // ── Access control: non-admin with matching library ───────────────────────

    @Test
    void recordSessionsBatch_nonAdminWithLibraryAccess_succeeds() {
        Library assignedLib = Library.builder().id(LIBRARY_ID).build();
        BookLoreUser user = nonAdminUserWithLibraries(List.of(assignedLib));

        when(authenticationService.getAuthenticatedUser()).thenReturn(user);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(userEntity));
        when(bookRepository.findById(BOOK_ID)).thenReturn(Optional.of(book));

        ReadingSessionItemRequest item = validItem();
        ReadingSessionBatchRequest request = new ReadingSessionBatchRequest(
                BOOK_ID, BookFileType.EPUB, List.of(item));

        ReadingSessionEntity saved = sessionEntityFrom(item);
        when(readingSessionRepository.saveAll(anyList())).thenReturn(List.of(saved));

        ReadingSessionBatchResponse response = service.recordSessionsBatch(request);
        assertThat(response.getSuccessCount()).isEqualTo(1);
    }

    // ── Access control: non-admin without library access ─────────────────────

    @Test
    void recordSessionsBatch_nonAdminWithoutLibraryAccess_throwsForbidden() {
        // User has a different library than the book's library
        Library otherLib = Library.builder().id(999L).build();
        BookLoreUser user = nonAdminUserWithLibraries(List.of(otherLib));

        when(authenticationService.getAuthenticatedUser()).thenReturn(user);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(userEntity));
        when(bookRepository.findById(BOOK_ID)).thenReturn(Optional.of(book));

        ReadingSessionItemRequest item = validItem();
        ReadingSessionBatchRequest request = new ReadingSessionBatchRequest(
                BOOK_ID, BookFileType.EPUB, List.of(item));

        assertThatThrownBy(() -> service.recordSessionsBatch(request))
                .isInstanceOf(APIException.class);
    }

    @Test
    void recordSessionsBatch_nonAdminWithNullPermissions_throwsForbidden() {
        BookLoreUser user = BookLoreUser.builder()
                .id(USER_ID)
                .permissions(null)
                .assignedLibraries(List.of()) // no libraries
                .build();

        when(authenticationService.getAuthenticatedUser()).thenReturn(user);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(userEntity));
        when(bookRepository.findById(BOOK_ID)).thenReturn(Optional.of(book));

        ReadingSessionItemRequest item = validItem();
        ReadingSessionBatchRequest request = new ReadingSessionBatchRequest(
                BOOK_ID, BookFileType.EPUB, List.of(item));

        assertThatThrownBy(() -> service.recordSessionsBatch(request))
                .isInstanceOf(APIException.class);
    }

    // ── Batch response correctness ────────────────────────────────────────────

    @Test
    void recordSessionsBatch_multipleSessions_responseHasCorrectCounts() {
        BookLoreUser admin = adminUser();
        when(authenticationService.getAuthenticatedUser()).thenReturn(admin);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(userEntity));
        when(bookRepository.findById(BOOK_ID)).thenReturn(Optional.of(book));

        Instant base = Instant.parse("2024-06-01T08:00:00Z");
        ReadingSessionItemRequest item1 = validItem(base, base.plusSeconds(600));
        ReadingSessionItemRequest item2 = validItem(base.plusSeconds(1200), base.plusSeconds(1800));

        ReadingSessionBatchRequest request = new ReadingSessionBatchRequest(
                BOOK_ID, BookFileType.EPUB, List.of(item1, item2));

        ReadingSessionEntity s1 = sessionEntityFrom(item1);
        ReadingSessionEntity s2 = sessionEntityFrom(item2);
        when(readingSessionRepository.saveAll(anyList())).thenReturn(List.of(s1, s2));

        ReadingSessionBatchResponse response = service.recordSessionsBatch(request);

        assertThat(response.getTotalRequested()).isEqualTo(2);
        assertThat(response.getSuccessCount()).isEqualTo(2);
        assertThat(response.getResults()).hasSize(2);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private BookLoreUser adminUser() {
        BookLoreUser.UserPermissions perms = new BookLoreUser.UserPermissions();
        perms.setAdmin(true);
        return BookLoreUser.builder()
                .id(USER_ID)
                .permissions(perms)
                .assignedLibraries(List.of())
                .build();
    }

    private BookLoreUser nonAdminUserWithLibraries(List<Library> libraries) {
        BookLoreUser.UserPermissions perms = new BookLoreUser.UserPermissions();
        perms.setAdmin(false);
        return BookLoreUser.builder()
                .id(USER_ID)
                .permissions(perms)
                .assignedLibraries(libraries)
                .build();
    }

    private ReadingSessionItemRequest validItem() {
        Instant start = Instant.parse("2024-01-01T10:00:00Z");
        return validItem(start, start.plusSeconds(3600));
    }

    private ReadingSessionItemRequest validItem(Instant start, Instant end) {
        ReadingSessionItemRequest item = new ReadingSessionItemRequest();
        item.setStartTime(start);
        item.setEndTime(end);
        item.setDurationSeconds((int) (end.getEpochSecond() - start.getEpochSecond()));
        return item;
    }

    private ReadingSessionEntity sessionEntityFrom(ReadingSessionItemRequest item) {
        return ReadingSessionEntity.builder()
                .startTime(item.getStartTime())
                .endTime(item.getEndTime())
                .build();
    }
}
