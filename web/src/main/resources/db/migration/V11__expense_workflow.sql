-- V11__expense_workflow.sql
-- Approval workflow and where the money came from.
--
-- status:     receptionist entries start PENDING until an admin approves or rejects them.
--             Existing rows, and rows the desktop app still inserts, default to APPROVED.
-- paid_from:  RECEPTION (the till), SAFE or BANK. NULL on rows inserted by the desktop app,
--             which only knows paid_from_storage; queries fall back to that column.

ALTER TABLE expense ADD COLUMN status TEXT NOT NULL DEFAULT 'APPROVED'
    CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'));
ALTER TABLE expense ADD COLUMN paid_from TEXT
    CHECK (paid_from IS NULL OR paid_from IN ('RECEPTION', 'SAFE', 'BANK'));
ALTER TABLE expense ADD COLUMN created_by_user_id INTEGER;
ALTER TABLE expense ADD COLUMN approved_at TEXT;
ALTER TABLE expense ADD COLUMN rejection_reason TEXT;

UPDATE expense
SET paid_from = CASE WHEN paid_from_storage = 1 THEN 'RECEPTION' ELSE 'BANK' END
WHERE paid_from IS NULL;

CREATE INDEX IF NOT EXISTS idx_expense_status ON expense(status);
CREATE INDEX IF NOT EXISTS idx_expense_created_by ON expense(created_by_user_id);
