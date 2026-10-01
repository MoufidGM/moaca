-- V5__expense_paid_from_storage.sql
-- Flag whether an expense was paid out of the local cash storage (default: yes).
-- Only expenses with paid_from_storage = 1 reduce the storage balance.

ALTER TABLE expense ADD COLUMN paid_from_storage INTEGER NOT NULL DEFAULT 1;
