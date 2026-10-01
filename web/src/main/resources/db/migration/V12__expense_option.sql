-- V12__expense_option.sql
-- Choice lists for the expense form. Categories marked admin_only (salaries, bills, rent…)
-- are not offered to receptionists. Managed by the super admin from M3 on.

CREATE TABLE IF NOT EXISTS expense_option (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    kind TEXT NOT NULL CHECK (kind IN ('CATEGORY', 'ACTIVITY')),
    name TEXT NOT NULL COLLATE NOCASE,
    admin_only INTEGER NOT NULL DEFAULT 0,
    active INTEGER NOT NULL DEFAULT 1,
    sort_order INTEGER NOT NULL DEFAULT 100,
    UNIQUE (kind, name)
);

-- Categories reception can use for day-to-day spending
INSERT OR IGNORE INTO expense_option (kind, name, admin_only, sort_order) VALUES
    ('CATEGORY', 'Supplies', 0, 10),
    ('CATEGORY', 'Maintenance', 0, 20),
    ('CATEGORY', 'Cleaning', 0, 30),
    ('CATEGORY', 'Transport', 0, 40),
    ('CATEGORY', 'Salary advance', 0, 50),
    ('CATEGORY', 'Other', 0, 90);

-- Admin-only categories
INSERT OR IGNORE INTO expense_option (kind, name, admin_only, sort_order) VALUES
    ('CATEGORY', 'Salaries', 1, 100),
    ('CATEGORY', 'Electricity', 1, 110),
    ('CATEGORY', 'Water', 1, 120),
    ('CATEGORY', 'Gas', 1, 130),
    ('CATEGORY', 'Heating', 1, 140),
    ('CATEGORY', 'Phone/Internet', 1, 150),
    ('CATEGORY', 'Rent', 1, 160),
    ('CATEGORY', 'Insurance', 1, 170),
    ('CATEGORY', 'Taxes', 1, 180),
    ('CATEGORY', 'Equipment', 1, 190);

-- What an expense is for: the income departments plus the cost centres already in use
INSERT OR IGNORE INTO expense_option (kind, name, sort_order) VALUES
    ('ACTIVITY', 'General', 10),
    ('ACTIVITY', 'Terrain', 20),
    ('ACTIVITY', 'Padel', 30),
    ('ACTIVITY', 'Gym', 40),
    ('ACTIVITY', 'Park', 50),
    ('ACTIVITY', 'Mini Golf', 60),
    ('ACTIVITY', 'Ping Pong', 70),
    ('ACTIVITY', 'Academy', 80),
    ('ACTIVITY', 'Taekwondo', 90),
    ('ACTIVITY', 'Shoes', 100),
    ('ACTIVITY', 'Drinks', 110),
    ('ACTIVITY', 'Danse', 120),
    ('ACTIVITY', 'Cleaning', 130),
    ('ACTIVITY', 'Events', 140),
    ('ACTIVITY', 'Salon', 150),
    ('ACTIVITY', 'Technical', 160);
