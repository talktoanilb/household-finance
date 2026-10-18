-- V2: make statement-import idempotency hold even when account_id is NULL.
-- V1's unique index on (account_id, external_ref) ignores rows with a NULL account_id,
-- because NULLs never collide in a unique index, so a re-imported line could be inserted twice.
-- The new index scopes by household and treats "no account" as account 0.
DROP INDEX transactions_account_external_ref_uidx;

CREATE UNIQUE INDEX transactions_external_ref_uidx
    ON transactions (household_id, COALESCE(account_id, 0), external_ref)
    WHERE external_ref IS NOT NULL;
