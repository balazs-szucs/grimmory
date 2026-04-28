-- V141__Add_sort_name_to_author.sql

-- Add the column using basic ANSI SQL
ALTER TABLE author ADD sort_name VARCHAR(255);

-- Standardize names before processing
UPDATE author SET name = TRIM(name);

-- Initial backfill using SQL logic: "First Middle Last" -> "Last, First Middle"
UPDATE author
SET sort_name = CASE
    WHEN name LIKE '% %' THEN
        CONCAT(
            SUBSTRING(name, LENGTH(name) - INSTR(REVERSE(name), ' ') + 2),
            ', ',
            SUBSTRING(name, 1, LENGTH(name) - INSTR(REVERSE(name), ' '))
        )
    ELSE name
END;

-- Index (standard syntax)
CREATE INDEX idx_author_sort_name ON author(sort_name);
