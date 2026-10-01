-- V1__init.sql  (consolidated base schema)

CREATE TABLE IF NOT EXISTS daily_summary (
                                             id INTEGER PRIMARY KEY AUTOINCREMENT,
                                             log_date TEXT NOT NULL UNIQUE,

    -- top summary strip
                                             total_terrain REAL,
                                             total_padel REAL,
                                             total_gym REAL,
                                             total_park REAL,
                                             total_mini_golf REAL,
                                             total_ping_pong REAL,
                                             total_academy_foot REAL,
                                             total_taekwondo REAL,
                                             total_shoes REAL,

    -- totals & payments
                                             total_ttc REAL,
                                             total_ht REAL,
                                             total_cash REAL,
                                             total_card REAL,
                                             total_cheque REAL,

    -- new fields
                                             file_path TEXT,
                                             drinks_qty_total INTEGER,
                                             drinks_amount_total REAL,

                                             created_at TEXT DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS line_item (
                                         id INTEGER PRIMARY KEY AUTOINCREMENT,
                                         log_date TEXT NOT NULL,
                                         point_de_vente TEXT NOT NULL,
                                         prestation TEXT,
                                         quantite INTEGER,
                                         prix REAL,
                                         total REAL
);
