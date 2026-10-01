-- V13__expense_attachment.sql
-- Receipts (photo or PDF) attached to an expense. Files live on the encrypted data volume
-- under a random name; only metadata is stored here.

CREATE TABLE IF NOT EXISTS expense_attachment (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    expense_id INTEGER NOT NULL REFERENCES expense(id) ON DELETE CASCADE,
    original_name TEXT NOT NULL,
    stored_name TEXT NOT NULL UNIQUE,
    content_type TEXT NOT NULL,
    size_bytes INTEGER NOT NULL,
    sha256 TEXT NOT NULL,
    uploaded_by TEXT NOT NULL,
    uploaded_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_expense_attachment_expense ON expense_attachment(expense_id);
