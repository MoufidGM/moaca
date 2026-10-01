-- V9__app_user.sql
-- Web app accounts. Roles: RECEPTIONIST, ADMIN, SUPER_ADMIN.

CREATE TABLE IF NOT EXISTS app_user (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL COLLATE NOCASE UNIQUE,
    display_name TEXT NOT NULL,
    password_hash TEXT NOT NULL,                 -- Argon2id
    role TEXT NOT NULL CHECK (role IN ('RECEPTIONIST', 'ADMIN', 'SUPER_ADMIN')),
    enabled INTEGER NOT NULL DEFAULT 1,

    totp_secret TEXT,                            -- AES-GCM encrypted; NULL until enrollment starts
    totp_enabled INTEGER NOT NULL DEFAULT 0,     -- 1 once the first code has been confirmed
    totp_last_step INTEGER,                      -- last accepted 30-second step (blocks code replay)

    failed_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until INTEGER,                        -- epoch milliseconds; NULL when not locked

    allowed_devices TEXT,                        -- comma-separated device names; NULL = any approved device
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at TEXT
);
