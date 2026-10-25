-- V7: staging for bill and receipt photos (M1f).
-- A photo is read, its proposed values wait here, and nothing reaches `transactions` until the user confirms
-- values that pass the Java checks. The photo itself and its OCR text are not stored: only the file hash
-- (to refuse the same photo twice) and the proposed values, which are cleared once the upload is committed or cancelled.

CREATE TABLE document_imports (
    id                  BIGSERIAL PRIMARY KEY,
    household_id        BIGINT      NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    file_sha256         CHAR(64)    NOT NULL,
    kind                TEXT        NOT NULL CHECK (kind IN ('BILL', 'RECEIPT', 'UNKNOWN')),
    status              TEXT        NOT NULL CHECK (status IN ('NEEDS_CONFIRMATION', 'COMMITTED', 'CANCELLED')),
    proposed_vendor     TEXT,
    proposed_amount     NUMERIC(12, 2) CHECK (proposed_amount >= 0),
    proposed_subtotal   NUMERIC(12, 2) CHECK (proposed_subtotal >= 0),
    proposed_tax        NUMERIC(12, 2) CHECK (proposed_tax >= 0),
    proposed_date       DATE,           -- purchase date for a receipt, due date for a bill
    uploaded_by_user_id BIGINT      REFERENCES app_users (id) ON DELETE SET NULL,
    transaction_id      BIGINT      REFERENCES transactions (id) ON DELETE SET NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ
);
-- The same photo cannot be uploaded twice for one household, unless the earlier attempt was cancelled.
CREATE UNIQUE INDEX document_imports_file_uidx
    ON document_imports (household_id, file_sha256)
    WHERE status <> 'CANCELLED';
