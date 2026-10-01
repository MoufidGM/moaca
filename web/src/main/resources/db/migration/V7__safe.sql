-- V7__safe.sql
-- Physical safe. Money comes IN from the storage every 10-15 days (entered manually),
-- and goes OUT to the bank (BANK) or to pay other expenses (OUT).
-- Safe balance = SUM(IN) - SUM(BANK) - SUM(OUT), always all-time (it is physical cash).
-- Transfers of type IN are also deducted from the storage balance.

CREATE TABLE IF NOT EXISTS safe_movement (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    movement_date TEXT NOT NULL,            -- yyyy-MM-dd
    type TEXT NOT NULL,                     -- IN / BANK / OUT
    amount REAL NOT NULL,
    note TEXT,
    created_at TEXT DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_safe_movement_date ON safe_movement(movement_date);

INSERT OR IGNORE INTO app_setting(key, value) VALUES ('safe.visible', '1');
