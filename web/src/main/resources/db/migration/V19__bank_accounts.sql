-- V19__bank_accounts.sql
-- Two bank accounts, tracked: the Association's (everything) and Tiki Taka's (the restaurant).
-- Their balances are computed from what the app already knows — card and cheque payments,
-- cash taken to the bank, expenses paid from an account — plus these movements for what the
-- app cannot know on its own: other money in (subsidies, refunds), bank fees, transfers
-- between the two accounts, and corrections to the statement balance.
--
-- An expense paid from Tiki Taka's account is stored as paid_from = BANK with till = RESTAURANT,
-- the same way its till is RECEPTION + RESTAURANT (the expense table's CHECK constraints stay).

CREATE TABLE IF NOT EXISTS bank_movement (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account TEXT NOT NULL CHECK (account IN ('ASSOCIATION', 'RESTAURANT')),
    movement_date TEXT NOT NULL,
    type TEXT NOT NULL CHECK (type IN ('IN', 'OUT', 'TRANSFER_IN', 'TRANSFER_OUT', 'ADJUST_IN', 'ADJUST_OUT')),
    amount REAL NOT NULL,
    note TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_bank_movement_account ON bank_movement(account, movement_date);
