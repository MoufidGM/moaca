-- V14__daily_import.sql
-- Every daily-log upload, accepted or refused, with the checks that ran on it.
-- Accepted originals are kept on disk (stored_name) so any figure can be traced to its file.

CREATE TABLE IF NOT EXISTS daily_import (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    log_date TEXT,                 -- NULL when the file name held no valid date
    original_name TEXT NOT NULL,
    stored_name TEXT,              -- NULL for refused uploads (file not kept)
    sha256 TEXT,
    size_bytes INTEGER,
    status TEXT NOT NULL CHECK (status IN ('IMPORTED', 'REPLACED', 'REJECTED')),
    total_ttc REAL,
    warnings TEXT,                 -- one warning per line; NULL when the checks all passed
    message TEXT,
    uploaded_by TEXT NOT NULL,
    uploaded_at TEXT NOT NULL,
    reviewed_by TEXT,              -- an admin marked the warnings as checked
    reviewed_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_daily_import_date ON daily_import(log_date);
CREATE INDEX IF NOT EXISTS idx_daily_import_sha ON daily_import(sha256);
CREATE INDEX IF NOT EXISTS idx_daily_import_uploaded ON daily_import(uploaded_at);
