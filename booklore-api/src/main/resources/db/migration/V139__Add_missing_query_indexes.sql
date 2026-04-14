-- Missing indexes for frequently-queried columns without index support.
-- Prevents full table scans on FK lookups and WHERE-clause columns.

-- book_file.book_id: FK used in repository methods (count, filter, join by book)
CREATE INDEX IF NOT EXISTS idx_book_file_book_id ON book_file (book_id);

-- book_file.current_hash: used in deduplication lookups (findByCurrentHash*)
CREATE INDEX IF NOT EXISTS idx_book_file_current_hash ON book_file (current_hash);

-- book_file.is_book: frequently filtered in WHERE clauses
CREATE INDEX IF NOT EXISTS idx_book_file_is_book ON book_file (is_book);

-- book.library_path_id: FK used in path-scoped queries and library rescans
CREATE INDEX IF NOT EXISTS idx_book_library_path_id ON book (library_path_id);

-- refresh_token.user_id: FK used in findAllByUserAndRevokedFalse, deleteByUser
CREATE INDEX IF NOT EXISTS idx_refresh_token_user_id ON refresh_token (user_id);

-- oidc_session: composite for cleanup queries (deleteByRevokedTrueAndCreatedAtBefore)
CREATE INDEX IF NOT EXISTS idx_oidc_session_revoked_created ON oidc_session (revoked, created_at);

-- metadata_fetch_jobs.completed_at: used in job cleanup (deleteAllByCompletedAtBefore)
CREATE INDEX IF NOT EXISTS idx_metadata_fetch_jobs_completed ON metadata_fetch_jobs (completed_at);

-- bookdrop_file.status: used in paginated queries (findAllByStatus)
CREATE INDEX IF NOT EXISTS idx_bookdrop_file_status ON bookdrop_file (status);
