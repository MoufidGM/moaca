-- V22__expense_import_files.sql
-- Every uploaded expense sheet is kept on disk and recorded here, like the daily log files in
-- daily_import, so admins can find any file by date. unit: which part of the center the sheet
-- is for (CENTER, RESTAURANT, SALON).

CREATE TABLE IF NOT EXISTS expense_import (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    unit TEXT NOT NULL DEFAULT 'CENTER',
    original_name TEXT NOT NULL,
    stored_name TEXT NOT NULL,
    sha256 TEXT,
    size_bytes INTEGER,
    rows_saved INTEGER NOT NULL,
    rows_skipped INTEGER NOT NULL,
    total REAL NOT NULL,
    from_date TEXT,                -- earliest and latest expense dates in the sheet
    to_date TEXT,
    status TEXT NOT NULL,          -- APPROVED (an admin's sheet) or PENDING (waiting for approval)
    uploaded_by TEXT NOT NULL,
    uploaded_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_expense_import_to_date ON expense_import(to_date);
