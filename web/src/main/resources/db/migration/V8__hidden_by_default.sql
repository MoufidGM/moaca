-- V8__hidden_by_default.sql
-- Balance (formerly Storage) and Safe amounts are hidden by default (eye icon reveals them).

UPDATE app_setting SET value = '0' WHERE key IN ('storage.visible', 'safe.visible');
