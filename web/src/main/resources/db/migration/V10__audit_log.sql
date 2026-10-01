-- V10__audit_log.sql
-- Who changed what, when, from where. Append-only: the triggers refuse any UPDATE or DELETE,
-- so a mistaken script or tool cannot rewrite history.

CREATE TABLE IF NOT EXISTS audit_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    at TEXT NOT NULL,              -- ISO-8601 instant, UTC
    username TEXT NOT NULL,
    action TEXT NOT NULL,          -- e.g. EXPENSE_CREATE, EXPENSE_APPROVE, DAILY_LOG_IMPORT
    entity TEXT,                   -- expense, daily_summary, cash_movement, safe_movement, ...
    entity_id TEXT,
    details TEXT,
    client_ip TEXT
);

CREATE INDEX IF NOT EXISTS idx_audit_log_at ON audit_log(at);

CREATE TRIGGER IF NOT EXISTS audit_log_no_update BEFORE UPDATE ON audit_log
BEGIN
    SELECT RAISE(ABORT, 'audit_log is append-only');
END;

CREATE TRIGGER IF NOT EXISTS audit_log_no_delete BEFORE DELETE ON audit_log
BEGIN
    SELECT RAISE(ABORT, 'audit_log is append-only');
END;
