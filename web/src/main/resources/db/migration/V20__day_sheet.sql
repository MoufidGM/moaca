-- V20__day_sheet.sql
-- The day sheet replaces the daily Excel log: a price list per unit (the center, Tiki Taka,
-- the Salon), and for each day the quantities sold, kept line by line. Saving a sheet writes
-- the same totals the file import wrote (daily_summary, restaurant_sales, salon_sales), so
-- every page and report keeps working unchanged. Also: Box and Dance as departments of their
-- own, and "frequent expenses" the reception can enter with one tap.

-- ---------- two more departments (the daily log file never had them) ----------
ALTER TABLE daily_summary ADD COLUMN total_box REAL;
ALTER TABLE daily_summary ADD COLUMN total_dance REAL;
UPDATE expense_option SET cost_rule = 'REVENUE', income_column = 'total_dance', part_of = NULL, active = 1
WHERE kind = 'ACTIVITY' AND name = 'Danse';
INSERT OR IGNORE INTO expense_option (kind, name, sort_order, cost_rule, income_column) VALUES ('ACTIVITY', 'Box', 95, 'REVENUE', 'total_box');

-- ---------- price list ----------
-- unit CENTER: department is the daily_summary column the line counts in (total_terrain…,
-- drinks_amount_total). RESTAURANT and SALON: department is a free heading (Plats, Boissons…).
-- kind DISCOUNT lines subtract. A price of 0 means "type the amount" (credits, advances).
CREATE TABLE IF NOT EXISTS price_item (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    unit TEXT NOT NULL CHECK (unit IN ('CENTER', 'RESTAURANT', 'SALON')),
    department TEXT NOT NULL,
    name TEXT NOT NULL,
    price REAL NOT NULL DEFAULT 0,
    kind TEXT NOT NULL DEFAULT 'ITEM' CHECK (kind IN ('ITEM', 'DISCOUNT')),
    sort_order INTEGER NOT NULL DEFAULT 100,
    active INTEGER NOT NULL DEFAULT 1
);
CREATE INDEX IF NOT EXISTS idx_price_item_unit ON price_item(unit, department, sort_order);

-- The center's list, as on the daily log sheet
INSERT INTO price_item (unit, department, name, price, kind, sort_order) VALUES
    ('CENTER', 'total_terrain', '5 vs 5 — jour', 250, 'ITEM', 10),
    ('CENTER', 'total_terrain', '7 vs 7 — jour', 420, 'ITEM', 20),
    ('CENTER', 'total_terrain', '9 vs 9 — jour', 190, 'ITEM', 30),
    ('CENTER', 'total_terrain', '5 vs 5 — soir', 300, 'ITEM', 40),
    ('CENTER', 'total_terrain', '7 vs 7 — soir', 490, 'ITEM', 50),
    ('CENTER', 'total_terrain', '9 vs 9 — soir', 900, 'ITEM', 60),
    ('CENTER', 'total_terrain', 'Remise', 0, 'DISCOUNT', 90),
    ('CENTER', 'total_padel', 'Padel', 280, 'ITEM', 10),
    ('CENTER', 'total_padel', 'Plus raquette', 10, 'ITEM', 20),
    ('CENTER', 'total_mini_golf', 'Enfant', 30, 'ITEM', 10),
    ('CENTER', 'total_mini_golf', 'Adulte', 40, 'ITEM', 20),
    ('CENTER', 'total_ping_pong', 'Ping pong', 30, 'ITEM', 10),
    ('CENTER', 'total_park', 'Park', 40, 'ITEM', 10),
    ('CENTER', 'total_park', 'Chaussettes', 15, 'ITEM', 20),
    ('CENTER', 'total_park', 'Remise', 0, 'DISCOUNT', 90),
    ('CENTER', 'total_gym', 'Séance', 50, 'ITEM', 10),
    ('CENTER', 'total_gym', '1 mois', 500, 'ITEM', 20),
    ('CENTER', 'total_gym', 'Réabonnement 1 mois', 400, 'ITEM', 30),
    ('CENTER', 'total_gym', '3 mois', 950, 'ITEM', 40),
    ('CENTER', 'total_gym', 'Réabonnement 3 mois', 850, 'ITEM', 50),
    ('CENTER', 'total_gym', '12 mois', 2600, 'ITEM', 60),
    ('CENTER', 'total_gym', 'Avance 3 mois', 0, 'ITEM', 70),
    ('CENTER', 'total_gym', 'Crédit', 0, 'ITEM', 80),
    ('CENTER', 'total_gym', 'Remise', 0, 'DISCOUNT', 90),
    ('CENTER', 'total_academy_foot', 'Activité', 50, 'ITEM', 10),
    ('CENTER', 'total_academy_foot', '1 mois', 500, 'ITEM', 20),
    ('CENTER', 'total_academy_foot', '1 mois (600)', 600, 'ITEM', 30),
    ('CENTER', 'total_academy_foot', '3 mois', 1300, 'ITEM', 40),
    ('CENTER', 'total_academy_foot', 'Chaussettes', 50, 'ITEM', 50),
    ('CENTER', 'total_academy_foot', 'Crédit', 0, 'ITEM', 80),
    ('CENTER', 'total_academy_foot', 'Remise', 0, 'DISCOUNT', 90),
    ('CENTER', 'total_taekwondo', '1 mois', 200, 'ITEM', 10),
    ('CENTER', 'total_taekwondo', '1 mois (170)', 170, 'ITEM', 20),
    ('CENTER', 'total_taekwondo', 'Crédit', 0, 'ITEM', 80),
    ('CENTER', 'total_taekwondo', 'Remise', 0, 'DISCOUNT', 90),
    ('CENTER', 'total_shoes', 'Kelme', 400, 'ITEM', 10),
    ('CENTER', 'total_shoes', 'Joma', 400, 'ITEM', 20),
    ('CENTER', 'drinks_amount_total', 'Eau 50 cl', 4, 'ITEM', 10),
    ('CENTER', 'drinks_amount_total', 'Eau 1,5 l', 8, 'ITEM', 20),
    ('CENTER', 'drinks_amount_total', 'Boisson 6 dh', 6, 'ITEM', 30),
    ('CENTER', 'drinks_amount_total', 'Boisson 8 dh', 8, 'ITEM', 40),
    ('CENTER', 'drinks_amount_total', 'Boisson 10 dh', 10, 'ITEM', 50),
    ('CENTER', 'drinks_amount_total', 'Canette', 8, 'ITEM', 60);

-- ---------- day sheets ----------
CREATE TABLE IF NOT EXISTS day_sheet (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    unit TEXT NOT NULL CHECK (unit IN ('CENTER', 'RESTAURANT', 'SALON')),
    sheet_date TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED')),
    card REAL NOT NULL DEFAULT 0,
    cheque REAL NOT NULL DEFAULT 0,
    people INTEGER,                 -- covers (restaurant) or clients (salon)
    note TEXT,
    opened_by TEXT NOT NULL,
    updated_by TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    closed_by TEXT,
    closed_at TEXT,
    UNIQUE (unit, sheet_date)
);

-- What was sold, with the name and price of the day (the price list may change later)
CREATE TABLE IF NOT EXISTS day_sheet_line (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    sheet_id INTEGER NOT NULL REFERENCES day_sheet(id) ON DELETE CASCADE,
    price_item_id INTEGER,
    department TEXT NOT NULL,
    name TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('ITEM', 'DISCOUNT', 'OTHER')),
    unit_price REAL NOT NULL,
    quantity REAL NOT NULL,
    amount REAL NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 100
);
CREATE INDEX IF NOT EXISTS idx_day_sheet_line_sheet ON day_sheet_line(sheet_id);

-- ---------- frequent expenses (one tap on the expense form) ----------
CREATE TABLE IF NOT EXISTS expense_preset (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL COLLATE NOCASE UNIQUE,
    category TEXT NOT NULL,
    activity TEXT NOT NULL,
    amount REAL,
    paid_from TEXT NOT NULL DEFAULT 'RECEPTION',
    sort_order INTEGER NOT NULL DEFAULT 100,
    active INTEGER NOT NULL DEFAULT 1
);
