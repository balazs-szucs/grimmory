package org.booklore.service.kobo;

import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.HardcoverSyncSettings;
import org.booklore.model.dto.KoboSyncSettings;
import org.booklore.model.dto.Shelf;
import org.booklore.model.dto.request.ShelfCreateRequest;
import org.booklore.model.entity.KoboUserSettingsEntity;
import org.booklore.model.entity.ShelfEntity;
import org.booklore.model.enums.IconType;
import org.booklore.model.enums.ShelfType;
import org.booklore.repository.KoboUserSettingsRepository;
import org.booklore.service.ShelfService;
import org.booklore.service.hardcover.HardcoverSyncSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class KoboSettingsService {

    private final KoboUserSettingsRepository repository;
    private final AuthenticationService authenticationService;
    private final ShelfService shelfService;
    private final HardcoverSyncSettingsService hardcoverSyncSettingsService;
    private final KoboLibrarySnapshotService koboLibrarySnapshotService;  // Added for forceFullSync

    @Transactional(readOnly = true)
    public KoboSyncSettings getCurrentUserSettings() {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        KoboUserSettingsEntity entity = repository.findByUserId(user.getId())
                .orElseGet(() -> initDefaultSettings(user.getId()));
        return mapToDto(entity);
    }

    @Transactional
    public KoboSyncSettings createOrUpdateToken() {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        String newToken = generateToken();

        KoboUserSettingsEntity entity = repository.findByUserId(user.getId())
                .map(existing -> {
                    existing.setToken(newToken);
                    return existing;
                })
                .orElseGet(() -> KoboUserSettingsEntity.builder()
                        .userId(user.getId())
                        .token(newToken)
                        .syncEnabled(false)
                        .build());

        ensureKoboShelfExists(user.getId());
        repository.save(entity);

        return mapToDto(entity);
    }

    @Transactional
    public KoboSyncSettings updateSettings(KoboSyncSettings settings) {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        KoboUserSettingsEntity entity = repository.findByUserId(user.getId()).orElseGet(() -> initDefaultSettings(user.getId()));

        if (settings.isSyncEnabled() != entity.isSyncEnabled()) {
            Shelf userKoboShelf = shelfService.getUserKoboShelf();
            if (!settings.isSyncEnabled()) {
                if (userKoboShelf != null) {
                    shelfService.deleteShelf(userKoboShelf.getId());
                }
            } else {
                ensureKoboShelfExists(user.getId());
            }
            entity.setSyncEnabled(settings.isSyncEnabled());
        }

        if (settings.getProgressMarkAsReadingThreshold() != null) {
            entity.setProgressMarkAsReadingThreshold(settings.getProgressMarkAsReadingThreshold());
        }
        if (settings.getProgressMarkAsFinishedThreshold() != null) {
            entity.setProgressMarkAsFinishedThreshold(settings.getProgressMarkAsFinishedThreshold());
        }

        entity.setAutoAddToShelf(settings.isAutoAddToShelf());
        entity.setTwoWayProgressSync(settings.isTwoWayProgressSync());
        if (settings.getSyncItemLimit() > 0) {
            entity.setSyncItemLimit(settings.getSyncItemLimit());
        }

        repository.save(entity);
        return mapToDto(entity, hardcoverSyncSettingsService.getSettingsForUserId(user.getId()));
    }

    private KoboUserSettingsEntity initDefaultSettings(Long userId) {
        ensureKoboShelfExists(userId);
        KoboUserSettingsEntity entity = KoboUserSettingsEntity.builder()
                .userId(userId)
                .syncEnabled(false)
                .token(generateToken())
                .build();
        return repository.save(entity);
    }

    private void ensureKoboShelfExists(Long userId) {
        Optional<ShelfEntity> shelf = shelfService.getShelf(userId, ShelfType.KOBO.getName());
        if (shelf.isEmpty()) {
            shelfService.createShelf(
                    ShelfCreateRequest.builder()
                            .name(ShelfType.KOBO.getName())
                            .icon(ShelfType.KOBO.getIcon())
                            .iconType(IconType.PRIME_NG)
                            .build()
            );
        }
    }

    private String generateToken() {
        return UUID.randomUUID().toString();
    }

    private KoboSyncSettings mapToDto(KoboUserSettingsEntity entity) {
        HardcoverSyncSettings hardcoverSettings = hardcoverSyncSettingsService.getSettingsForUserId(entity.getUserId());
        return mapToDto(entity, hardcoverSettings);
    }

    private KoboSyncSettings mapToDto(KoboUserSettingsEntity entity, HardcoverSyncSettings hardcoverSettings) {
        KoboSyncSettings dto = new KoboSyncSettings();
        dto.setId(entity.getId());
        dto.setUserId(entity.getUserId().toString());
        dto.setToken(entity.getToken());
        dto.setSyncEnabled(entity.isSyncEnabled());
        dto.setKoboProxyEnabled(entity.isKoboProxyEnabled());  // NEW: Map proxy enabled
        dto.setProgressMarkAsReadingThreshold(entity.getProgressMarkAsReadingThreshold());
        dto.setProgressMarkAsFinishedThreshold(entity.getProgressMarkAsFinishedThreshold());
        dto.setAutoAddToShelf(entity.isAutoAddToShelf());
        dto.setTwoWayProgressSync(entity.isTwoWayProgressSync());
        dto.setSyncItemLimit(entity.getSyncItemLimit());
        if (hardcoverSettings != null) {
            dto.setHardcoverApiKey(hardcoverSettings.getHardcoverApiKey());
            dto.setHardcoverSyncEnabled(hardcoverSettings.isHardcoverSyncEnabled());
        } else {
            dto.setHardcoverSyncEnabled(false);
        }
        return dto;
    }

    /**
     * Get Kobo settings for a specific user by ID.
     */
    @Transactional(readOnly = true)
    public KoboSyncSettings getSettingsByUserId(Long userId) {
        return repository.findByUserId(userId)
                .map(this::mapToDto)
                .orElse(null);
    }

    /**
     * Check if Kobo proxy is enabled for current user.
     * Used by KoboThumbnailService for CDN redirect.
     */
    @Transactional(readOnly = true)
    public boolean isKoboProxyEnabled() {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        return repository.findByUserId(user.getId())
                .map(KoboUserSettingsEntity::isKoboProxyEnabled)
                .orElse(false);
    }

    /**
     * Force full Kobo sync by resetting sync state.
     * Deletes all snapshots and generates a new token, forcing the next sync to be a full initial sync.
     * Ported from Komga's "Force full kobo sync" feature.
     * 
     * Use case: When Kobo device becomes out of sync or user switches to a new device.
     * 
     * @param token Optional token to target a specific device. If null, regenerates token for all devices.
     * @return Updated settings with new token
     */
    @Transactional
    public KoboSyncSettings forceFullSync(String token) {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        
        // Generate new token (invalidates old sync tokens)
        String newToken = generateToken();
        
        // Delete all snapshots for this user (forces full resync)
        koboLibrarySnapshotService.deleteAllSnapshotsForUser(user.getId());
        
        // Update entity with new token
        KoboUserSettingsEntity entity = repository.findByUserId(user.getId())
                .orElseGet(() -> initDefaultSettings(user.getId()));
        entity.setToken(newToken);
        
        repository.save(entity);
        
        log.info("Forced full Kobo sync for user {} (new token generated, all snapshots deleted)", user.getId());
        
        return mapToDto(entity, hardcoverSyncSettingsService.getSettingsForUserId(user.getId()));
    }
    
    /**
     * Force full Kobo sync for current user (all devices).
     * Convenience method that regenerates token for all devices.
     */
    @Transactional
    public KoboSyncSettings forceFullSync() {
        return forceFullSync(null);
    }

}
