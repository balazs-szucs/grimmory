-- Add cover_hash column to track thumbnail/cover changes between sync points
ALTER TABLE kobo_library_snapshot_book ADD COLUMN cover_hash VARCHAR(255) NULL;

-- Add book_file_size column to track file size changes (Komga tracks both hash + size)
ALTER TABLE kobo_library_snapshot_book ADD COLUMN book_file_size BIGINT NULL;

-- Add read_progress_last_modified to detect reading progress changes between sync points
ALTER TABLE kobo_library_snapshot_book ADD COLUMN read_progress_last_modified TIMESTAMP(6) NULL;

-- Add configurable sync item limit per user (default 100, Komga default)
ALTER TABLE kobo_user_settings ADD COLUMN sync_item_limit INT NOT NULL DEFAULT 100;

-- Add kobo_proxy_enabled for CDN redirect and hybrid sync (Komga pattern)
ALTER TABLE kobo_user_settings ADD COLUMN kobo_proxy_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- Add index for better unsynced book query performance
CREATE INDEX idx_kobo_snapshot_book_snapshot_synced ON kobo_library_snapshot_book (snapshot_id, synced);
