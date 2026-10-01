-- V2__expenses.sql  (expense tracking)

CREATE TABLE IF NOT EXISTS expense (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    expense_date TEXT NOT NULL,           -- yyyy-MM-dd
    category TEXT NOT NULL,               -- Salaries, Electricity, Water, ...
    description TEXT,
    amount REAL NOT NULL,
    payment_method TEXT,                  -- CASH / CARD / CHEQUE / TRANSFER
    created_at TEXT DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_expense_date ON expense(expense_date);
CREATE INDEX IF NOT EXISTS idx_expense_category ON expense(category);
