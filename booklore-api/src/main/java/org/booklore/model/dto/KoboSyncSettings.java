package org.booklore.model.dto;


import lombok.Data;

/**
 * Kobo Sync Settings DTO.
 * 
 * Fields:
 * - syncEnabled: Enable/disable Kobo sync
 * - koboProxyEnabled: Proxy requests to Kobo store for hybrid sync (NEW - ported from Komga)
 * - progressMarkAsReadingThreshold: Progress % to mark as "reading"
 * - progressMarkAsFinishedThreshold: Progress % to mark as "finished"
 * - autoAddToShelf: Auto-add new books to Kobo shelf
 * - twoWayProgressSync: Sync progress both ways (device ↔ web reader)
 * - syncItemLimit: Books per sync batch (for continuation tokens)
 * - hardcover*: Optional Hardcover.com integration
 */
@Data
public class KoboSyncSettings {
    private Long id;
    private String userId;
    private String token;
    private boolean syncEnabled;
    private boolean koboProxyEnabled;  // NEW: Proxy to Kobo store
    private Float progressMarkAsReadingThreshold;
    private Float progressMarkAsFinishedThreshold;
    private boolean autoAddToShelf;
    private String hardcoverApiKey;
    private boolean hardcoverSyncEnabled;
    private boolean twoWayProgressSync;
    private int syncItemLimit = 100;
}
