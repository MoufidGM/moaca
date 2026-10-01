-- V17__password_change_required.sql
-- Accounts created or reset from the Administration page get a temporary password chosen by
-- the super admin. The user has to replace it at the next sign-in, so nobody but the user
-- knows the password that signs their entries.

ALTER TABLE app_user ADD COLUMN password_change_required INTEGER NOT NULL DEFAULT 0;
