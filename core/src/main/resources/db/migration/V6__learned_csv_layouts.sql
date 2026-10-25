-- V6: CSV layouts learned from the user (M1c). One row per account and file shape (header fingerprint):
-- once the user has confirmed what each column means, later files with the same columns import without questions.
-- A file whose columns differ has a different fingerprint, so the user is asked again.
CREATE TABLE csv_layouts (
    id                  BIGSERIAL PRIMARY KEY,
    household_id        BIGINT      NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    account_id          BIGINT      NOT NULL REFERENCES financial_accounts (id) ON DELETE CASCADE,
    header_fingerprint  TEXT        NOT NULL,
    date_col            INTEGER     NOT NULL CHECK (date_col >= 0),
    date_format         TEXT        NOT NULL,
    desc_col            INTEGER     NOT NULL CHECK (desc_col >= 0),
    amount_mode         TEXT        NOT NULL CHECK (amount_mode IN ('SINGLE', 'DEBIT_CREDIT')),
    amount_col          INTEGER     CHECK (amount_col >= 0),
    debit_col           INTEGER     CHECK (debit_col >= 0),
    credit_col          INTEGER     CHECK (credit_col >= 0),
    out_is_negative     BOOLEAN     NOT NULL,
    balance_col         INTEGER     CHECK (balance_col >= 0),
    confirmed_by_user_id BIGINT     REFERENCES app_users (id) ON DELETE SET NULL,
    confirmed_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (  (amount_mode = 'SINGLE' AND amount_col IS NOT NULL)
          OR (amount_mode = 'DEBIT_CREDIT' AND debit_col IS NOT NULL AND credit_col IS NOT NULL)),
    UNIQUE (account_id, header_fingerprint)
);
