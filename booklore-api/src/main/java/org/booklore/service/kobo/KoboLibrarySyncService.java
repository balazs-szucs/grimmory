package org.booklore.service.kobo;

import jakarta.servlet.http.HttpServletRequest;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.BookloreSyncToken;
import org.booklore.model.dto.kobo.*;
import org.booklore.model.entity.KoboLibrarySnapshotEntity;
import org.booklore.model.entity.KoboSnapshotBookEntity;
import org.booklore.model.entity.UserBookProgressEntity;
import org.booklore.repository.KoboDeletedBookProgressRepository;
import org.booklore.repository.UserBookProgressRepository;
import org.booklore.util.RequestUtils;
import org.booklore.util.kobo.BookloreSyncTokenGenerator;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Kobo Library Sync Service - Implements delta sync with SyncPoint pattern (ported from Komga).
 * 
 * Stability improvements ported from research:
 * - ✅ Continuation token support (x-kobo-sync: continue)
 * - ✅ Delta sync with 5 phases (added, changed, removed, progress changed, collections)
 * - ✅ Atomic snapshot operations
 * - ✅ Sync token size monitoring (NEW - GitHub Issue #2177 mitigation)
 * - ✅ Kobo store proxy with graceful fallback
 */
@AllArgsConstructor
@Service
@Slf4j
public class KoboLibrarySyncService {

    // Sync token size threshold - alert when approaching reverse proxy buffer limits
    // Authelia/Traefik/Nginx default buffer: 4KB (4096 bytes)
    // Alert at 3000 chars to provide safety margin (GitHub Issue #2177)
    private static final int SYNC_TOKEN_SIZE_WARNING_THRESHOLD = 3000;
    private static final int SYNC_TOKEN_SIZE_CRITICAL_THRESHOLD = 3500;

    private final BookloreSyncTokenGenerator tokenGenerator;
    private final KoboLibrarySnapshotService koboLibrarySnapshotService;
    private final KoboEntitlementService entitlementService;
    private final KoboDeletedBookProgressRepository koboDeletedBookProgressRepository;
    private final UserBookProgressRepository userBookProgressRepository;
    private final KoboServerProxy koboServerProxy;
    private final ObjectMapper objectMapper;
    private final KoboSettingsService koboSettingsService;

    @Transactional
    public ResponseEntity<?> syncLibrary(BookLoreUser user, String token) {
        HttpServletRequest request = RequestUtils.getCurrentRequest();
        BookloreSyncToken syncToken = Optional.ofNullable(tokenGenerator.fromRequestHeaders(request)).orElse(new BookloreSyncToken());

        // Find the ongoing sync point, else create one (mirrors Komga's pattern)
        KoboLibrarySnapshotEntity currSnapshot = koboLibrarySnapshotService
                .findByIdAndUserId(syncToken.getOngoingSyncPointId(), user.getId())
                .orElseGet(() -> koboLibrarySnapshotService.create(user.getId()));

        // Find the last successful sync point, if any
        Optional<KoboLibrarySnapshotEntity> prevSnapshot = koboLibrarySnapshotService
                .findByIdAndUserId(syncToken.getLastSuccessfulSyncPointId(), user.getId());

        log.debug("Library sync from SyncPoint {}, to SyncPoint: {}", prevSnapshot.map(KoboLibrarySnapshotEntity::getId).orElse(null), currSnapshot.getId());

        List<Entitlement> entitlements = new ArrayList<>();
        boolean shouldContinueSync;

        // Use configurable sync item limit (default 100, matching Komga)
        int syncItemLimit = koboSettingsService.getCurrentUserSettings().getSyncItemLimit();
        if (syncItemLimit <= 0) {
            syncItemLimit = 100;
        }

        if (prevSnapshot.isPresent()) {
            // Delta sync — find books added/changed/removed/readProgressChanged
            int maxRemaining = syncItemLimit;

            koboLibrarySnapshotService.updateSyncedStatusForExistingBooks(prevSnapshot.get().getId(), currSnapshot.getId());

            // Phase 1: Newly added books
            Page<KoboSnapshotBookEntity> addedPage = koboLibrarySnapshotService
                    .getNewlyAddedBooks(prevSnapshot.get().getId(), currSnapshot.getId(), PageRequest.of(0, maxRemaining), user.getId());
            maxRemaining -= addedPage.getNumberOfElements();
            shouldContinueSync = addedPage.hasNext();

            // Phase 2: Changed books (file, metadata, cover, or size changes)
            Page<KoboSnapshotBookEntity> changedPage = Page.empty();
            if (addedPage.isLast() && maxRemaining > 0) {
                changedPage = koboLibrarySnapshotService
                        .getChangedBooks(prevSnapshot.get().getId(), currSnapshot.getId(), PageRequest.of(0, maxRemaining));
                maxRemaining -= changedPage.getNumberOfElements();
                shouldContinueSync = shouldContinueSync || changedPage.hasNext();
            }

            // Phase 3: Removed books
            Page<KoboSnapshotBookEntity> removedPage = Page.empty();
            if (changedPage.isLast() && maxRemaining > 0) {
                removedPage = koboLibrarySnapshotService
                        .getRemovedBooks(prevSnapshot.get().getId(), currSnapshot.getId(), user.getId(), PageRequest.of(0, maxRemaining));
                maxRemaining -= removedPage.getNumberOfElements();
                shouldContinueSync = shouldContinueSync || removedPage.hasNext();
            }

            // Phase 4: Books with changed reading progress (mirrors Komga's takeBooksReadProgressChanged)
            Page<KoboSnapshotBookEntity> readProgressChangedPage = Page.empty();
            if (removedPage.isLast() && maxRemaining > 0) {
                readProgressChangedPage = koboLibrarySnapshotService
                        .getBooksReadProgressChanged(prevSnapshot.get().getId(), currSnapshot.getId(), PageRequest.of(0, maxRemaining));
                maxRemaining -= readProgressChangedPage.getNumberOfElements();
                shouldContinueSync = shouldContinueSync || readProgressChangedPage.hasNext();
            }

            Set<Long> addedIds = addedPage.getContent().stream()
                    .map(KoboSnapshotBookEntity::getBookId)
                    .collect(Collectors.toUnmodifiableSet());
            Set<Long> changedIds = changedPage.getContent().stream()
                    .map(KoboSnapshotBookEntity::getBookId)
                    .collect(Collectors.toUnmodifiableSet());
            Set<Long> removedIds = removedPage.getContent().stream()
                    .map(KoboSnapshotBookEntity::getBookId)
                    .collect(Collectors.toUnmodifiableSet());
            Set<Long> readProgressChangedIds = readProgressChangedPage.getContent().stream()
                    .map(KoboSnapshotBookEntity::getBookId)
                    .collect(Collectors.toUnmodifiableSet());

            // Generate entitlements for each phase
            entitlements.addAll(entitlementService.generateNewEntitlements(addedIds, token));

            // Komga sends NewEntitlement + ChangedProductMetadata for changed books
            entitlements.addAll(entitlementService.generateNewEntitlements(changedIds, token));
            entitlements.addAll(entitlementService.generateChangedEntitlements(changedIds, token, false));

            entitlements.addAll(entitlementService.generateChangedEntitlements(removedIds, token, true));

            // Changed books + readProgressChanged both get ChangedReadingState (mirrors Komga lines 396-406)
            Set<Long> booksNeedingReadingStateUpdate = new LinkedHashSet<>();
            booksNeedingReadingStateUpdate.addAll(changedIds);
            booksNeedingReadingStateUpdate.addAll(readProgressChangedIds);
            if (!booksNeedingReadingStateUpdate.isEmpty()) {
                entitlements.addAll(entitlementService.generateChangedReadingStatesForBooks(booksNeedingReadingStateUpdate, user.getId()));
            }

            log.debug("Library sync: {} added, {} changed, {} removed, {} reading progress changed",
                    addedPage.getNumberOfElements(), changedPage.getNumberOfElements(),
                    removedPage.getNumberOfElements(), readProgressChangedPage.getNumberOfElements());

            if (!shouldContinueSync) {
                entitlements.addAll(syncReadingStatesToKobo(user.getId(), currSnapshot.getId()));
                entitlements.addAll(entitlementService.generateTags());
            }
        } else {
            // Initial sync — no previous snapshot, sync everything
            // Single page fetch, matching Komga's pattern (no while loop)
            Page<KoboSnapshotBookEntity> booksPage = koboLibrarySnapshotService
                    .getUnsyncedBooks(currSnapshot.getId(), PageRequest.of(0, syncItemLimit));
            shouldContinueSync = booksPage.hasNext();

            Set<Long> ids = booksPage.getContent().stream().map(KoboSnapshotBookEntity::getBookId).collect(Collectors.toSet());
            entitlements.addAll(entitlementService.generateNewEntitlements(ids, token));

            log.debug("Library initial sync: {} books", booksPage.getNumberOfElements());

            if (!shouldContinueSync) {
                entitlements.addAll(syncReadingStatesToKobo(user.getId(), currSnapshot.getId()));
                entitlements.addAll(entitlementService.generateTags());
            }
        }

        // Merge Kobo store sync response — only trigger once all local updates are processed
        // Wrapping in try-catch for graceful failure (mirrors Komga lines 474-488)
        // GATED on koboProxyEnabled setting (fixes proxy always being called)
        if (!shouldContinueSync && koboSettingsService.getCurrentUserSettings().isKoboProxyEnabled()) {
            try {
                ResponseEntity<JsonNode> koboStoreResponse = koboServerProxy.proxyCurrentRequest(null, true);
                Collection<Entitlement> syncResultsKobo = Optional.ofNullable(koboStoreResponse.getBody())
                        .map(body -> {
                            try {
                                List<Entitlement> results = new ArrayList<>();
                                if (body.isArray()) {
                                    for (JsonNode node : body) {
                                        if (node.has("NewEntitlement")) {
                                            results.add(objectMapper.treeToValue(node, NewEntitlement.class));
                                        } else if (node.has("ChangedEntitlement")) {
                                            results.add(objectMapper.treeToValue(node, ChangedEntitlement.class));
                                        } else {
                                            log.debug("Skipping unknown entitlement type in Kobo response: {}", node);
                                        }
                                    }
                                }
                                return results;
                            } catch (Exception e) {
                                log.warn("Failed to parse Kobo store sync response: {}", e.getMessage());
                                return Collections.<Entitlement>emptyList();
                            }
                        })
                        .orElse(Collections.emptyList());

                entitlements.addAll(syncResultsKobo);

                shouldContinueSync = "continue".equalsIgnoreCase(
                        Optional.ofNullable(koboStoreResponse.getHeaders().getFirst(KoboHeaders.X_KOBO_SYNC)).orElse("")
                );

                String koboSyncTokenHeader = koboStoreResponse.getHeaders().getFirst(KoboHeaders.X_KOBO_SYNCTOKEN);
                if (koboSyncTokenHeader != null) {
                    BookloreSyncToken koboToken = tokenGenerator.fromBase64(koboSyncTokenHeader);
                    syncToken.setRawKoboSyncToken(koboToken.getRawKoboSyncToken());
                }
            } catch (Exception e) {
                log.warn("Kobo store sync endpoint failure, continuing with local data only: {}", e.getMessage());
            }
        }

        // Update sync token to send back to Kobo device
        if (shouldContinueSync) {
            syncToken.setOngoingSyncPointId(currSnapshot.getId());
        } else {
            // Cleanup old sync point if it exists
            prevSnapshot.ifPresent(sp -> koboLibrarySnapshotService.deleteById(sp.getId()));
            koboDeletedBookProgressRepository.deleteBySnapshotIdAndUserId(syncToken.getOngoingSyncPointId(), user.getId());
            syncToken.setOngoingSyncPointId(null);
            syncToken.setLastSuccessfulSyncPointId(currSnapshot.getId());
        }

        // Encode sync token and monitor size (GitHub Issue #2177 - sync token buffer overflow)
        String encodedToken = tokenGenerator.toBase64(syncToken);
        monitorSyncTokenSize(encodedToken, user.getId());
        
        // Active truncation: Strip raw Kobo store token if total size exceeds safe limit
        // This prevents reverse proxy buffer overflow while maintaining local sync functionality
        String finalToken = truncateSyncTokenIfOversized(encodedToken, syncToken);

        return ResponseEntity.ok()
                .header(KoboHeaders.X_KOBO_SYNC, shouldContinueSync ? "continue" : "")
                .header(KoboHeaders.X_KOBO_SYNCTOKEN, finalToken)
                .body(entitlements);
    }

    /**
     * Truncate sync token if it exceeds safe size for reverse proxies.
     * Strips the raw Kobo store token portion (local sync still works without it).
     * GitHub Issue #2177: Authelia/Traefik/Nginx default 4KB buffer
     */
    private String truncateSyncTokenIfOversized(String encodedToken, BookloreSyncToken originalToken) {
        // Critical threshold: 3800 chars (leave 200 char safety margin for 4KB buffer)
        if (encodedToken.length() > 3800) {
            log.warn("Sync token exceeds safe size ({} chars), stripping raw Kobo store token to prevent proxy buffer overflow", 
                    encodedToken.length());
            
            // Create truncated token without raw Kobo store token
            BookloreSyncToken truncated = BookloreSyncToken.builder()
                    .ongoingSyncPointId(originalToken.getOngoingSyncPointId())
                    .lastSuccessfulSyncPointId(originalToken.getLastSuccessfulSyncPointId())
                    .rawKoboSyncToken(null)  // Strip Kobo store token
                    .build();
            
            String truncatedToken = tokenGenerator.toBase64(truncated);
            log.info("Truncated sync token from {} to {} chars (stripped raw Kobo store token)", 
                    encodedToken.length(), truncatedToken.length());
            return truncatedToken;
        }
        
        return encodedToken;
    }

    /**
     * Monitor sync token size to prevent reverse proxy buffer overflow (GitHub Issue #2177).
     * X-Kobo-Synctoken header can exceed 3500+ characters for large libraries,
     * causing 401 errors with Authelia/Traefik/Nginx (default 4KB buffer).
     */
    private void monitorSyncTokenSize(String encodedToken, Long userId) {
        int tokenSize = encodedToken.length();
        
        if (tokenSize >= SYNC_TOKEN_SIZE_CRITICAL_THRESHOLD) {
            log.error("CRITICAL: Kobo sync token size ({}) exceeds critical threshold ({}). " +
                    "This may cause 401 errors with reverse proxies. Consider: " +
                    "1) Increasing proxy buffer size (Authelia: header_buffer_size=16384), " +
                    "2) Reducing sync item limit, " +
                    "3) Forcing full sync to reset token. User ID: {}", 
                    tokenSize, SYNC_TOKEN_SIZE_CRITICAL_THRESHOLD, userId);
        } else if (tokenSize >= SYNC_TOKEN_SIZE_WARNING_THRESHOLD) {
            log.warn("WARNING: Kobo sync token size ({}) approaching threshold ({}). " +
                    "If you experience 401 errors, increase reverse proxy buffer size. User ID: {}", 
                    tokenSize, SYNC_TOKEN_SIZE_WARNING_THRESHOLD, userId);
        } else if (tokenSize > 2000) {
            log.debug("Kobo sync token size: {} bytes (user: {})", tokenSize, userId);
        }
    }

    private List<ChangedReadingState> syncReadingStatesToKobo(Long userId, String snapshotId) {
        List<UserBookProgressEntity> booksNeedingSync =
                userBookProgressRepository.findAllBooksNeedingKoboSync(userId, snapshotId);

        if (!koboSettingsService.getCurrentUserSettings().isTwoWayProgressSync()) {
            booksNeedingSync = booksNeedingSync.stream()
                    .filter(p -> needsStatusSync(p) || needsKoboProgressSync(p))
                    .toList();
        }

        if (booksNeedingSync.isEmpty()) {
            return Collections.emptyList();
        }

        List<ChangedReadingState> changedStates = entitlementService.generateChangedReadingStates(booksNeedingSync);

        Instant sentTime = Instant.now();
        for (UserBookProgressEntity progress : booksNeedingSync) {
            if (needsStatusSync(progress)) {
                progress.setKoboStatusSentTime(sentTime);
            }
            if (needsProgressSync(progress)) {
                progress.setKoboProgressSentTime(sentTime);
            }
        }
        userBookProgressRepository.saveAll(booksNeedingSync);

        log.info("Synced {} reading states to Kobo", changedStates.size());
        return changedStates;
    }

    private boolean needsStatusSync(UserBookProgressEntity progress) {
        Instant modifiedTime = progress.getReadStatusModifiedTime();
        if (modifiedTime == null) {
            return false;
        }
        Instant sentTime = progress.getKoboStatusSentTime();
        return sentTime == null || modifiedTime.isAfter(sentTime);
    }

    private boolean needsKoboProgressSync(UserBookProgressEntity progress) {
        Instant sentTime = progress.getKoboProgressSentTime();
        Instant receivedTime = progress.getKoboProgressReceivedTime();
        return receivedTime != null && (sentTime == null || receivedTime.isAfter(sentTime));
    }

    private boolean needsProgressSync(UserBookProgressEntity progress) {
        if (needsKoboProgressSync(progress)) {
            return true;
        }

        if (koboSettingsService.getCurrentUserSettings().isTwoWayProgressSync()
                && progress.getEpubProgress() != null && progress.getEpubProgressPercent() != null) {
            Instant sentTime = progress.getKoboProgressSentTime();
            Instant lastReadTime = progress.getLastReadTime();
            if (lastReadTime != null && (sentTime == null || lastReadTime.isAfter(sentTime))) {
                return true;
            }
        }

        return false;
    }
}
