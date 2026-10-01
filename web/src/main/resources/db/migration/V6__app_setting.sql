-- V6__app_setting.sql
-- Simple key/value settings (storage year mode, storage visibility, ...).

CREATE TABLE IF NOT EXISTS app_setting (
    key TEXT PRIMARY KEY,
    value TEXT
);

-- storage.year_mode: CARRY (keep accumulating across years) or RESET (start from 0 every Jan 1)
INSERT OR IGNORE INTO app_setting(key, value) VALUES ('storage.year_mode', 'CARRY');
-- storage.visible: 1 = show the amount in the top bar, 0 = hidden (eye toggle)
INSERT OR IGNORE INTO app_setting(key, value) VALUES ('storage.visible', '1');
