-- V3__expense_people_activity.sql
-- Who entered / approved the expense, and which activity it is allocated to.

ALTER TABLE expense ADD COLUMN entered_by TEXT;
ALTER TABLE expense ADD COLUMN approved_by TEXT;
ALTER TABLE expense ADD COLUMN activity TEXT;   -- Terrain, Padel, Gym, Academy, ... or General

CREATE INDEX IF NOT EXISTS idx_expense_activity ON expense(activity);
