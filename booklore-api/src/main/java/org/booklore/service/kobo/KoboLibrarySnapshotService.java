package org.booklore.service.kobo;

import org.booklore.config.security.service.AuthenticationService;
import org.booklore.mapper.BookEntityToKoboSnapshotBookMapper;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.entity.*;
import org.booklore.model.enums.ShelfType;
import org.booklore.repository.KoboDeletedBookProgressRepository;
import org.booklore.repository.KoboLibrarySnapshotRepository;
import org.booklore.repository.KoboSnapshotBookRepository;
import org.booklore.repository.ShelfRepository;
import org.booklore.repository.UserBookProgressRepository;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@AllArgsConstructor
@Service
public class KoboLibrarySnapshotService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(KoboLibrarySnapshotService.class);

    private final KoboLibrarySnapshotRepository koboLibrarySnapshotRepository;
    private final KoboSnapshotBookRepository koboSnapshotBookRepository;
    private final ShelfRepository shelfRepository;
    private final BookEntityToKoboSnapshotBookMapper mapper;
    private final KoboDeletedBookProgressRepository koboDeletedBookProgressRepository;
    private final KoboCompatibilityService koboCompatibilityService;
    private final AuthenticationService authenticationService;
    private final UserBookProgressRepository userBookProgressRepository;

    @Transactional(readOnly = true)
    public Optional<KoboLibrarySnapshotEntity> findByIdAndUserId(String id, Long userId) {
        return koboLibrarySnapshotRepository.findByIdAndUserId(id, userId);
    }

    @Transactional
    public KoboLibrarySnapshotEntity create(Long userId) {
        KoboLibrarySnapshotEntity snapshot = KoboLibrarySnapshotEntity.builder()
                .id(UUID.randomUUID().toString())
                .userId(userId)
                .build();

        List<KoboSnapshotBookEntity> books = mapBooksToKoboSnapshotBook(getKoboShelf(userId), snapshot);
        snapshot.setBooks(books);

        return koboLibrarySnapshotRepository.save(snapshot);
    }

    @Transactional
    public Page<KoboSnapshotBookEntity> getUnsyncedBooks(String snapshotId, Pageable pageable) {
        Page<KoboSnapshotBookEntity> page = koboSnapshotBookRepository.findBySnapshot_IdAndSyncedFalse(snapshotId, pageable);
        List<Long> bookIds = page.getContent().stream()
                .map(KoboSnapshotBookEntity::getBookId)
                .toList();
        if (!bookIds.isEmpty()) {
            koboSnapshotBookRepository.markBooksSynced(snapshotId, bookIds);
        }
        return page;
    }

    @Transactional
    public void updateSyncedStatusForExistingBooks(String previousSnapshotId, String currentSnapshotId) {
        List<KoboSnapshotBookEntity> list = koboSnapshotBookRepository.findUnchangedBooksBetweenSnapshots(previousSnapshotId, currentSnapshotId);
        List<Long> unchangedBooks = list.stream()
                .map(KoboSnapshotBookEntity::getBookId)
                .toList();

        if (!unchangedBooks.isEmpty()) {
            koboSnapshotBookRepository.markBooksSynced(currentSnapshotId, unchangedBooks);
        }
    }

    @Transactional
    public Page<KoboSnapshotBookEntity> getNewlyAddedBooks(String previousSnapshotId, String currentSnapshotId, Pageable pageable, Long userId) {
        Page<KoboSnapshotBookEntity> page = koboSnapshotBookRepository.findNewlyAddedBooks(previousSnapshotId, currentSnapshotId, true, pageable);
        List<Long> newlyAddedBookIds = page.getContent().stream()
                .map(KoboSnapshotBookEntity::getBookId)
                .toList();

        if (!newlyAddedBookIds.isEmpty()) {
            koboSnapshotBookRepository.markBooksSynced(currentSnapshotId, newlyAddedBookIds);
        }

        return page;
    }

    @Transactional
    public Page<KoboSnapshotBookEntity> getRemovedBooks(String previousSnapshotId, String currentSnapshotId, Long userId, Pageable pageable) {
        Page<KoboSnapshotBookEntity> page = koboSnapshotBookRepository.findRemovedBooks(previousSnapshotId, currentSnapshotId, pageable);

        List<Long> bookIds = page.getContent().stream()
                .map(KoboSnapshotBookEntity::getBookId)
                .toList();

        if (!bookIds.isEmpty()) {
            List<KoboDeletedBookProgressEntity> progressEntities = bookIds.stream()
                    .map(bookId -> KoboDeletedBookProgressEntity.builder()
                            .bookIdSynced(bookId)
                            .snapshotId(currentSnapshotId)
                            .userId(userId)
                            .build())
                    .toList();

            koboDeletedBookProgressRepository.saveAll(progressEntities);
        }
        return page;
    }

    @Transactional
    public Page<KoboSnapshotBookEntity> getChangedBooks(String previousSnapshotId, String currentSnapshotId, Pageable pageable) {
        Page<KoboSnapshotBookEntity> page = koboSnapshotBookRepository.findChangedBooks(previousSnapshotId, currentSnapshotId, pageable);
        List<Long> changedBookIds = page.getContent().stream()
                .map(KoboSnapshotBookEntity::getBookId)
                .toList();

        if (!changedBookIds.isEmpty()) {
            koboSnapshotBookRepository.markBooksSynced(currentSnapshotId, changedBookIds);
        }

        return page;
    }

    /**
     * Find books whose reading progress has changed between two snapshots.
     * Mirrors Komga's SyncPointLifecycle.takeBooksReadProgressChanged.
     */
    @Transactional
    public Page<KoboSnapshotBookEntity> getBooksReadProgressChanged(String previousSnapshotId, String currentSnapshotId, Pageable pageable) {
        Page<KoboSnapshotBookEntity> page = koboSnapshotBookRepository.findBooksReadProgressChanged(previousSnapshotId, currentSnapshotId, pageable);
        List<Long> bookIds = page.getContent().stream()
                .map(KoboSnapshotBookEntity::getBookId)
                .toList();

        if (!bookIds.isEmpty()) {
            koboSnapshotBookRepository.markBooksSynced(currentSnapshotId, bookIds);
        }

        return page;
    }

    private ShelfEntity getKoboShelf(Long userId) {
        return shelfRepository
                .findByUserIdAndName(userId, ShelfType.KOBO.getName())
                .orElseThrow(() -> new NoSuchElementException(
                        String.format("Shelf '%s' not found for user %d", ShelfType.KOBO.getName(), userId)
                ));
    }

    private List<KoboSnapshotBookEntity> mapBooksToKoboSnapshotBook(ShelfEntity shelf, KoboLibrarySnapshotEntity snapshot) {
        Long userId = snapshot.getUserId();

        List<BookEntity> eligibleBooks = shelf.getBookEntities().stream()
                .filter(book -> isBookOwnedByUser(book, userId))
                .filter(koboCompatibilityService::isBookSupportedForKobo)
                .toList();

        // Batch-load user progress for all eligible books to populate readProgressLastModified
        Set<Long> bookIds = eligibleBooks.stream().map(BookEntity::getId).collect(Collectors.toSet());
        Map<Long, UserBookProgressEntity> progressByBookId = userBookProgressRepository
                .findByUserIdAndBookIdIn(userId, bookIds).stream()
                .collect(Collectors.toMap(p -> p.getBook().getId(), Function.identity(), (a, b) -> a));

        return eligibleBooks.stream()
                .map(book -> {
                    KoboSnapshotBookEntity snapshotBook = mapper.toKoboSnapshotBook(book);
                    snapshotBook.setSnapshot(snapshot);
                    snapshotBook.setFileHash(book.getPrimaryBookFile().getCurrentHash());
                    snapshotBook.setMetadataUpdatedAt(book.getMetadataUpdatedAt());

                    // Populate read progress last modified for delta sync detection
                    UserBookProgressEntity progress = progressByBookId.get(book.getId());
                    if (progress != null) {
                        Instant readProgressModified = latestOf(
                                progress.getLastReadTime(),
                                progress.getKoboProgressReceivedTime(),
                                progress.getReadStatusModifiedTime()
                        );
                        snapshotBook.setReadProgressLastModified(readProgressModified);
                    }
                    return snapshotBook;
                })
                .collect(Collectors.toList());
    }

    private Instant latestOf(Instant... instants) {
        Instant latest = null;
        for (Instant instant : instants) {
            if (instant != null && (latest == null || instant.isAfter(latest))) {
                latest = instant;
            }
        }
        return latest;
    }

    private boolean isBookOwnedByUser(BookEntity book, Long userId) {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        if (user.getPermissions().isAdmin()) {
            return true;
        }
        return book.getLibrary()
                .getUsers()
                .stream()
                .map(BookLoreUserEntity::getId)
                .anyMatch(id -> Objects.equals(id, userId));
    }

    public void deleteById(String id) {
        koboLibrarySnapshotRepository.deleteById(id);
    }

    /**
     * Delete all snapshots for a user (used for force full sync).
     * Ported from Komga's force full sync feature.
     */
    @Transactional
    public void deleteAllSnapshotsForUser(Long userId) {
        List<KoboLibrarySnapshotEntity> snapshots = koboLibrarySnapshotRepository.findByUserId(userId);
        for (KoboLibrarySnapshotEntity snapshot : snapshots) {
            koboLibrarySnapshotRepository.deleteById(snapshot.getId());
        }
        log.debug("Deleted {} snapshots for user {}", snapshots.size(), userId);
    }

}
