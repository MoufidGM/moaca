-- V4__cash_storage.sql
-- Local cash storage (caisse/safe). Deposits and withdrawals are recorded here;
-- every expense is paid out of the storage, so:
--   balance = SUM(deposits) - SUM(withdrawals) - SUM(all expenses)

CREATE TABLE IF NOT EXISTS cash_movement (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    movement_date TEXT NOT NULL,            -- yyyy-MM-dd
    type TEXT NOT NULL,                     -- DEPOSIT / WITHDRAWAL
    amount REAL NOT NULL,
    note TEXT,
    created_at TEXT DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_cash_movement_date ON cash_movement(movement_date);
