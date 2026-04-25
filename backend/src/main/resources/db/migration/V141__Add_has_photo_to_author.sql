ALTER TABLE author ADD COLUMN has_photo BOOLEAN NOT NULL DEFAULT FALSE;

-- Initialize has_photo based on existing files is in SQL,
