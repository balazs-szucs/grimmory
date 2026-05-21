-- Most critical for filtering and sorting
CREATE INDEX IF NOT EXISTS idx_book_library_deleted 
    ON book(library_id, deleted);

-- Index the first 255 characters of title for fast sorting and filtering without hitting key size limits
CREATE INDEX IF NOT EXISTS idx_book_metadata_title 
    ON book_metadata(title(255));
