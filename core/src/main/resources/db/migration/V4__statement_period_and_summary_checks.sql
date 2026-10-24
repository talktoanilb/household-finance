-- V4: remember each statement's period and balances so the next statement can be checked against it,
-- and allow the two new kinds of question that come from statement totals.
ALTER TABLE statement_imports
    ADD COLUMN period_start       DATE,
    ADD COLUMN period_end         DATE,
    ADD COLUMN beginning_balance  NUMERIC(12, 2),
    ADD COLUMN ending_balance     NUMERIC(12, 2);

ALTER TABLE ingestion_questions DROP CONSTRAINT ingestion_questions_kind_check;
ALTER TABLE ingestion_questions ADD CONSTRAINT ingestion_questions_kind_check
    CHECK (kind IN ('BALANCE_MISMATCH', 'DATE_IMPLAUSIBLE', 'SUMMARY_MISMATCH', 'STATEMENT_GAP'));
