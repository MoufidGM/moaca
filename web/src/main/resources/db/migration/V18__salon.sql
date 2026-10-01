-- V18__salon.sql
-- The Salon earns revenue of its own, entered day by day on its page by the reception (its
-- cash goes into the reception till, its card sales to the bank). Its expenses were already
-- recorded on the "Salon" activity; from now on they are its direct costs.

CREATE TABLE IF NOT EXISTS salon_sales (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    sale_date TEXT NOT NULL UNIQUE,
    cash REAL NOT NULL DEFAULT 0,
    card REAL NOT NULL DEFAULT 0,
    clients INTEGER,
    note TEXT,
    entered_by TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

-- Special income column 'salon': revenue comes from salon_sales, like 'restaurant' for Tiki Taka
UPDATE expense_option SET cost_rule = 'REVENUE', income_column = 'salon', part_of = NULL, active = 1, sort_order = 116
WHERE kind = 'ACTIVITY' AND name = 'Salon';
INSERT OR IGNORE INTO expense_option (kind, name, sort_order, cost_rule, income_column) VALUES
    ('ACTIVITY', 'Salon', 116, 'REVENUE', 'salon');
