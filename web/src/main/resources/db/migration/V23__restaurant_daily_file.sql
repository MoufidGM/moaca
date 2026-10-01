-- V23__restaurant_daily_file.sql
-- Tiki Taka's daily file carries four things: the day's sales, the card total, what the
-- family ate (not paid, tracked per person) and the expenses paid out of the till.
--
-- on_account: the family's consumption of the day, at menu value. It is not revenue (nobody
-- pays it) and not cash; it is kept so the day's figures match the file, and detailed per
-- person in family_consumption. salon_sales gets the same column, unused, so the two tables
-- read alike.
-- source_import_id: expenses created from a daily file remember which upload made them, so a
-- replaced file replaces its expenses instead of doubling them.

ALTER TABLE restaurant_sales ADD COLUMN on_account REAL NOT NULL DEFAULT 0;
ALTER TABLE salon_sales ADD COLUMN on_account REAL NOT NULL DEFAULT 0;
ALTER TABLE expense ADD COLUMN source_import_id INTEGER;
CREATE INDEX IF NOT EXISTS idx_expense_source_import ON expense(source_import_id);

CREATE TABLE IF NOT EXISTS family_consumption (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    sale_date TEXT NOT NULL,
    member TEXT NOT NULL,
    amount REAL NOT NULL,
    note TEXT,
    import_id INTEGER,              -- the daily_import row the line came from
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_family_consumption_date ON family_consumption(sale_date);
CREATE INDEX IF NOT EXISTS idx_family_consumption_member ON family_consumption(member);
