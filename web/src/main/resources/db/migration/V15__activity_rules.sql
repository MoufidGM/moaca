-- V15__activity_rules.sql
-- How each activity's costs are counted in the per-activity analysis, and splitting one
-- expense across several activities.
--
-- cost_rule (activities only):
--   REVENUE   earns revenue: income_column is its daily_summary column; its expenses are direct costs
--   SHARED    common cost (general, cleaning, technical…): spread over the revenue activities
--   SEPARATE  shown on its own line, costs only
--   PART_OF   counted as part of the revenue activity named in part_of

ALTER TABLE expense_option ADD COLUMN income_column TEXT;
ALTER TABLE expense_option ADD COLUMN cost_rule TEXT NOT NULL DEFAULT 'SEPARATE'
    CHECK (cost_rule IN ('REVENUE', 'SHARED', 'SEPARATE', 'PART_OF'));
ALTER TABLE expense_option ADD COLUMN part_of TEXT;

UPDATE expense_option SET cost_rule = 'REVENUE', income_column = 'total_terrain'       WHERE kind = 'ACTIVITY' AND name = 'Terrain';
UPDATE expense_option SET cost_rule = 'REVENUE', income_column = 'total_padel'         WHERE kind = 'ACTIVITY' AND name = 'Padel';
UPDATE expense_option SET cost_rule = 'REVENUE', income_column = 'total_gym'           WHERE kind = 'ACTIVITY' AND name = 'Gym';
UPDATE expense_option SET cost_rule = 'REVENUE', income_column = 'total_park'          WHERE kind = 'ACTIVITY' AND name = 'Park';
UPDATE expense_option SET cost_rule = 'REVENUE', income_column = 'total_mini_golf'     WHERE kind = 'ACTIVITY' AND name = 'Mini Golf';
UPDATE expense_option SET cost_rule = 'REVENUE', income_column = 'total_ping_pong'     WHERE kind = 'ACTIVITY' AND name = 'Ping Pong';
UPDATE expense_option SET cost_rule = 'REVENUE', income_column = 'total_academy_foot'  WHERE kind = 'ACTIVITY' AND name = 'Academy';
UPDATE expense_option SET cost_rule = 'REVENUE', income_column = 'total_taekwondo'     WHERE kind = 'ACTIVITY' AND name = 'Taekwondo';
UPDATE expense_option SET cost_rule = 'REVENUE', income_column = 'total_shoes'         WHERE kind = 'ACTIVITY' AND name = 'Shoes';
UPDATE expense_option SET cost_rule = 'REVENUE', income_column = 'drinks_amount_total' WHERE kind = 'ACTIVITY' AND name = 'Drinks';

UPDATE expense_option SET cost_rule = 'SHARED' WHERE kind = 'ACTIVITY' AND name IN ('General', 'Cleaning', 'Technical');
-- Danse, Salon and Events stay SEPARATE until an admin decides otherwise.

-- One expense split across activities. No rows = 100% to expense.activity.
CREATE TABLE IF NOT EXISTS expense_allocation (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    expense_id INTEGER NOT NULL REFERENCES expense(id) ON DELETE CASCADE,
    activity TEXT NOT NULL,
    percent REAL NOT NULL CHECK (percent > 0 AND percent <= 100),
    UNIQUE (expense_id, activity)
);

CREATE INDEX IF NOT EXISTS idx_expense_allocation_expense ON expense_allocation(expense_id);
