-- V21__three_daily_logs.sql
-- Days keep coming from Excel files, but there are three of them now: the center's daily log
-- (DL-…), Tiki Taka's (TT-…) and the Salon's (SA-…). daily_import records which one a file
-- was. The in-app day sheet (V20) is withdrawn: its tables go, their data was never used.

ALTER TABLE daily_import ADD COLUMN unit TEXT NOT NULL DEFAULT 'CENTER';

DROP TABLE IF EXISTS day_sheet_line;
DROP TABLE IF EXISTS day_sheet;
DROP TABLE IF EXISTS price_item;
