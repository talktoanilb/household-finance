-- V3: staging for statement imports (M1b).
-- Parsed lines wait here until every open question is answered; only then are they written to
-- `transactions`. Staged lines are deleted once an import is committed or cancelled.

CREATE TABLE statement_imports (
    id                  BIGSERIAL PRIMARY KEY,
    household_id        BIGINT      NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    account_id          BIGINT      NOT NULL REFERENCES financial_accounts (id),
    file_sha256         CHAR(64)    NOT NULL,
    layout              TEXT        NOT NULL,        -- e.g. CHASE_CHECKING
    status              TEXT        NOT NULL CHECK (status IN ('NEEDS_ANSWERS', 'COMMITTED', 'CANCELLED')),
    line_count          INTEGER     NOT NULL CHECK (line_count >= 0),
    imported_count      INTEGER     CHECK (imported_count >= 0),
    skipped_count       INTEGER     CHECK (skipped_count >= 0),   -- lines already present from an earlier import
    created_by_user_id  BIGINT      REFERENCES app_users (id) ON DELETE SET NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ
);
-- The same file cannot be imported twice for one account, unless the earlier attempt was cancelled.
CREATE UNIQUE INDEX statement_imports_file_uidx
    ON statement_imports (household_id, account_id, file_sha256)
    WHERE status <> 'CANCELLED';

CREATE TABLE staged_lines (
    id              BIGSERIAL PRIMARY KEY,
    import_id       BIGINT      NOT NULL REFERENCES statement_imports (id) ON DELETE CASCADE,
    line_no         INTEGER     NOT NULL CHECK (line_no >= 1),
    posted_on       DATE        NOT NULL,
    description     TEXT        NOT NULL,
    amount          NUMERIC(12, 2) NOT NULL CHECK (amount >= 0),
    direction       TEXT        NOT NULL CHECK (direction IN ('EXPENSE', 'INCOME')),
    status          TEXT        NOT NULL CHECK (status IN ('PENDING', 'POSTED')),
    balance         NUMERIC(12, 2),
    external_ref    TEXT        NOT NULL,
    excluded        BOOLEAN     NOT NULL DEFAULT FALSE,
    UNIQUE (import_id, line_no)
);

-- Questions for the user. line_no NULL means the question is about the whole file.
-- Messages are built from numbers and dates only, never from document text.
CREATE TABLE ingestion_questions (
    id                   BIGSERIAL PRIMARY KEY,
    household_id         BIGINT      NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    import_id            BIGINT      NOT NULL REFERENCES statement_imports (id) ON DELETE CASCADE,
    line_no              INTEGER     CHECK (line_no >= 1),
    kind                 TEXT        NOT NULL CHECK (kind IN ('BALANCE_MISMATCH', 'DATE_IMPLAUSIBLE')),
    message              TEXT        NOT NULL,
    status               TEXT        NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'ANSWERED')),
    answer               TEXT        CHECK (answer IN ('CONFIRM', 'EXCLUDE', 'CORRECT_AMOUNT')),
    corrected_amount     NUMERIC(12, 2) CHECK (corrected_amount >= 0),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    answered_at          TIMESTAMPTZ,
    answered_by_user_id  BIGINT      REFERENCES app_users (id) ON DELETE SET NULL
);
CREATE INDEX ingestion_questions_open_idx ON ingestion_questions (household_id, import_id) WHERE status = 'OPEN';
