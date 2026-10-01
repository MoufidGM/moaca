-- V16__activities_v2.sql
-- Activity structure v2: renamed activities and Tiki Taka (restaurant), cost headings on
-- categories, employees with activity splits, allocation keys for shared costs, restaurant
-- daily sales, a second till (the restaurant's), and the RESTAURANT_MANAGER role.

-- ---------- activities ----------
UPDATE expense_option SET name = 'Location de terrains' WHERE kind = 'ACTIVITY' AND name = 'Terrain';
UPDATE expense_option SET name = 'Académie'             WHERE kind = 'ACTIVITY' AND name = 'Academy';
UPDATE expense_option SET name = 'Arts Martiaux'        WHERE kind = 'ACTIVITY' AND name = 'Taekwondo';
UPDATE expense          SET activity = 'Location de terrains' WHERE activity = 'Terrain';
UPDATE expense          SET activity = 'Académie'             WHERE activity IN ('Academy', 'Académie');
UPDATE expense          SET activity = 'Arts Martiaux'        WHERE activity = 'Taekwondo';
UPDATE expense_allocation SET activity = 'Location de terrains' WHERE activity = 'Terrain';
UPDATE expense_allocation SET activity = 'Académie'             WHERE activity = 'Academy';
UPDATE expense_allocation SET activity = 'Arts Martiaux'        WHERE activity = 'Taekwondo';
UPDATE expense_option SET part_of = 'Location de terrains' WHERE kind = 'ACTIVITY' AND part_of = 'Terrain';
UPDATE expense_option SET part_of = 'Académie'             WHERE kind = 'ACTIVITY' AND part_of = 'Academy';
UPDATE expense_option SET part_of = 'Arts Martiaux'        WHERE kind = 'ACTIVITY' AND part_of = 'Taekwondo';

-- Tiki Taka: earns revenue from restaurant_sales (special income column 'restaurant')
INSERT OR IGNORE INTO expense_option (kind, name, sort_order, cost_rule, income_column) VALUES
    ('ACTIVITY', 'Tiki Taka', 115, 'REVENUE', 'restaurant');

-- ---------- cost headings on categories ----------
-- SALARIES, MAINTENANCE, PURCHASES, UTILITIES, OTHER
ALTER TABLE expense_option ADD COLUMN heading TEXT NOT NULL DEFAULT 'OTHER'
    CHECK (heading IN ('SALARIES', 'MAINTENANCE', 'PURCHASES', 'UTILITIES', 'OTHER'));

INSERT OR IGNORE INTO expense_option (kind, name, admin_only, sort_order) VALUES
    ('CATEGORY', 'Packs & jerseys', 1, 195),
    ('CATEGORY', 'Food & drinks', 0, 35);

UPDATE expense_option SET heading = 'SALARIES'    WHERE kind = 'CATEGORY' AND name IN ('Salaries', 'Salary advance');
UPDATE expense_option SET heading = 'MAINTENANCE' WHERE kind = 'CATEGORY' AND name IN ('Maintenance', 'Cleaning');
UPDATE expense_option SET heading = 'PURCHASES'   WHERE kind = 'CATEGORY' AND name IN ('Supplies', 'Equipment', 'Packs & jerseys', 'Insurance', 'Food & drinks');
UPDATE expense_option SET heading = 'UTILITIES'   WHERE kind = 'CATEGORY' AND name IN ('Electricity', 'Water', 'Gas', 'Heating', 'Phone/Internet');

-- ---------- employees ----------
CREATE TABLE IF NOT EXISTS employee (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL COLLATE NOCASE UNIQUE,
    job TEXT,
    monthly_salary REAL,
    active INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- How an employee's pay is divided between activities (percentages totalling 100).
CREATE TABLE IF NOT EXISTS employee_split (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    employee_id INTEGER NOT NULL REFERENCES employee(id) ON DELETE CASCADE,
    activity TEXT NOT NULL,
    percent REAL NOT NULL CHECK (percent > 0 AND percent <= 100),
    UNIQUE (employee_id, activity)
);

ALTER TABLE expense ADD COLUMN employee_id INTEGER;
CREATE INDEX IF NOT EXISTS idx_expense_employee ON expense(employee_id);

-- ---------- allocation keys for shared costs ----------
-- kind UTILITIES: electricity, water, gas, heating, phone/internet not tied to one activity
-- kind COMMON:    every other common cost (activities set as SHARED)
-- No rows for a kind = spread by revenue, as before.
CREATE TABLE IF NOT EXISTS cost_key (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    kind TEXT NOT NULL CHECK (kind IN ('UTILITIES', 'COMMON')),
    activity TEXT NOT NULL,
    percent REAL NOT NULL CHECK (percent > 0 AND percent <= 100),
    UNIQUE (kind, activity)
);

-- ---------- Tiki Taka (restaurant) ----------
CREATE TABLE IF NOT EXISTS restaurant_sales (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    sale_date TEXT NOT NULL UNIQUE,
    cash REAL NOT NULL DEFAULT 0,
    card REAL NOT NULL DEFAULT 0,
    covers INTEGER,
    note TEXT,
    entered_by TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

-- Which till an amount concerns when it is cash: RECEPTION or RESTAURANT
ALTER TABLE expense ADD COLUMN till TEXT NOT NULL DEFAULT 'RECEPTION' CHECK (till IN ('RECEPTION', 'RESTAURANT'));
ALTER TABLE cash_movement ADD COLUMN till TEXT NOT NULL DEFAULT 'RECEPTION' CHECK (till IN ('RECEPTION', 'RESTAURANT'));
ALTER TABLE safe_movement ADD COLUMN source TEXT NOT NULL DEFAULT 'RECEPTION' CHECK (source IN ('RECEPTION', 'RESTAURANT'));

-- ---------- RESTAURANT_MANAGER role (CHECK constraint needs a table rebuild) ----------
CREATE TABLE app_user_v2 (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL COLLATE NOCASE UNIQUE,
    display_name TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('RECEPTIONIST', 'RESTAURANT_MANAGER', 'ADMIN', 'SUPER_ADMIN')),
    enabled INTEGER NOT NULL DEFAULT 1,
    totp_secret TEXT,
    totp_enabled INTEGER NOT NULL DEFAULT 0,
    totp_last_step INTEGER,
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until INTEGER,
    allowed_devices TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at TEXT
);
INSERT INTO app_user_v2 (id, username, display_name, password_hash, role, enabled, totp_secret, totp_enabled, totp_last_step,
                         failed_attempts, locked_until, allowed_devices, created_at, last_login_at)
    SELECT id, username, display_name, password_hash, role, enabled, totp_secret, totp_enabled, totp_last_step,
           failed_attempts, locked_until, allowed_devices, created_at, last_login_at FROM app_user;
DROP TABLE app_user;
ALTER TABLE app_user_v2 RENAME TO app_user;
