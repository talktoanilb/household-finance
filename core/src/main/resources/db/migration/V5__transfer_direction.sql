-- Money moving between the household's own accounts (e.g. a card payment) is neither spending nor earnings.
-- TRANSFER_IN / TRANSFER_OUT are seen from the account the line belongs to. Reports must leave them out.
ALTER TABLE transactions DROP CONSTRAINT transactions_direction_check;
ALTER TABLE transactions ADD CONSTRAINT transactions_direction_check
    CHECK (direction IN ('EXPENSE', 'INCOME', 'TRANSFER_IN', 'TRANSFER_OUT'));

ALTER TABLE staged_lines DROP CONSTRAINT staged_lines_direction_check;
ALTER TABLE staged_lines ADD CONSTRAINT staged_lines_direction_check
    CHECK (direction IN ('EXPENSE', 'INCOME', 'TRANSFER_IN', 'TRANSFER_OUT'));
