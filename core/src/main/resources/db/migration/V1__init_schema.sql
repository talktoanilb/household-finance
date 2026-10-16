-- V1: core household schema (Phase 1: spending, subscriptions, insurance, health, anomalies).
-- Security rules from the spec:
--   * Card and account numbers: last 4 digits only (never full numbers).
--   * Policy numbers: last 4 digits only.
--   * Email: stores Gmail message IDs and extracted metadata, never message bodies.
--   * Money: NUMERIC(12,2), never floating point.

CREATE TABLE households (
    id          BIGSERIAL PRIMARY KEY,
    name        TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Separate logins per household member, no shared passwords.
CREATE TABLE app_users (
    id              BIGSERIAL PRIMARY KEY,
    household_id    BIGINT      NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    email           TEXT        NOT NULL UNIQUE,
    display_name    TEXT        NOT NULL,
    password_hash   TEXT        NOT NULL,        -- bcrypt/argon2 hash, never plaintext
    totp_secret_enc BYTEA,                       -- encrypted at the application layer; NULL until 2FA is enrolled
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX app_users_household_idx ON app_users (household_id);

-- Category codes from the spec; editable per household later (table keeps that possible).
CREATE TABLE categories (
    code    TEXT PRIMARY KEY,
    label   TEXT NOT NULL
);
INSERT INTO categories (code, label) VALUES
    ('groceries',     'Groceries'),
    ('utilities',     'Utilities'),
    ('insurance',     'Insurance'),
    ('subscriptions', 'Subscriptions'),
    ('health',        'Health'),
    ('transport',     'Transport'),
    ('home',          'Home'),
    ('dining',        'Dining'),
    ('entertainment', 'Entertainment'),
    ('other',         'Other');

-- Bank accounts and credit cards. A replaced card links to its successor
-- (e.g. Citi Costco Visa ...2876 replaced by ...6371).
CREATE TABLE financial_accounts (
    id                      BIGSERIAL PRIMARY KEY,
    household_id            BIGINT      NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    institution             TEXT        NOT NULL,
    account_type            TEXT        NOT NULL CHECK (account_type IN ('CHECKING', 'SAVINGS', 'CREDIT_CARD')),
    display_name            TEXT        NOT NULL,
    last4                   CHAR(4)     NOT NULL CHECK (last4 SIMILAR TO '[0-9]{4}'),
    status                  TEXT        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REPLACED', 'CLOSED')),
    replaced_by_account_id  BIGINT      REFERENCES financial_accounts (id),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (household_id, institution, last4)
);

-- Uploaded files (bill photos, statements, policy PDFs, EOBs). Files live in encrypted
-- storage; this row holds only the location, a hash for dedupe, and OCR text.
CREATE TABLE documents (
    id                  BIGSERIAL PRIMARY KEY,
    household_id        BIGINT      NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    uploaded_by_user_id BIGINT      REFERENCES app_users (id) ON DELETE SET NULL,
    doc_type            TEXT        NOT NULL CHECK (doc_type IN (
                                        'BILL', 'RECEIPT', 'STATEMENT', 'INSURANCE_POLICY', 'EOB', 'MEDICAL_BILL', 'OTHER')),
    storage_key         TEXT        NOT NULL,        -- pointer to encrypted object, never the raw bytes
    mime_type           TEXT        NOT NULL,
    sha256              CHAR(64)    NOT NULL,
    ocr_text            TEXT,                        -- short retention; cleared with the document
    ocr_status          TEXT        NOT NULL DEFAULT 'PENDING' CHECK (ocr_status IN ('PENDING', 'DONE', 'FAILED', 'NOT_APPLICABLE')),
    uploaded_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ,                 -- retention: photos deleted after 90 days by default
    UNIQUE (household_id, sha256)                    -- same file uploaded twice is stored once
);
CREATE INDEX documents_household_uploaded_idx ON documents (household_id, uploaded_at);
CREATE INDEX documents_expires_idx ON documents (expires_at) WHERE expires_at IS NOT NULL;

-- Every spending item: from a bill photo, manual entry, a statement line, or a matched email.
CREATE TABLE transactions (
    id                  BIGSERIAL PRIMARY KEY,
    household_id        BIGINT      NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    account_id          BIGINT      REFERENCES financial_accounts (id),     -- NULL for cash/manual entries
    document_id         BIGINT      REFERENCES documents (id) ON DELETE SET NULL,
    posted_on           DATE        NOT NULL,
    vendor              TEXT        NOT NULL,
    amount              NUMERIC(12, 2) NOT NULL CHECK (amount >= 0),
    direction           TEXT        NOT NULL CHECK (direction IN ('EXPENSE', 'INCOME')),
    category_code       TEXT        REFERENCES categories (code),
    source              TEXT        NOT NULL CHECK (source IN ('PHOTO', 'MANUAL', 'STATEMENT', 'EMAIL')),
    status              TEXT        NOT NULL DEFAULT 'POSTED' CHECK (status IN ('PENDING', 'POSTED', 'FLAGGED', 'RECONCILED')),
    external_ref        TEXT,                                               -- statement line fingerprint, for idempotent imports
    entered_by_user_id  BIGINT      REFERENCES app_users (id) ON DELETE SET NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX transactions_household_date_idx ON transactions (household_id, posted_on);
CREATE INDEX transactions_household_category_idx ON transactions (household_id, category_code);
-- Re-importing the same statement must not create duplicate rows.
CREATE UNIQUE INDEX transactions_account_external_ref_uidx
    ON transactions (account_id, external_ref)
    WHERE external_ref IS NOT NULL;

-- Recurring charges, from statements and confirmation emails.
CREATE TABLE subscriptions (
    id                  BIGSERIAL PRIMARY KEY,
    household_id        BIGINT      NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    vendor              TEXT        NOT NULL,
    expected_amount     NUMERIC(12, 2) NOT NULL CHECK (expected_amount >= 0),
    cadence             TEXT        NOT NULL CHECK (cadence IN ('WEEKLY', 'MONTHLY', 'ANNUAL')),
    account_id          BIGINT      REFERENCES financial_accounts (id),       -- which card it charges; updated after replacements
    next_renewal_on     DATE,
    status              TEXT        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'CANCEL_REQUESTED', 'CANCELED', 'UNKNOWN')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX subscriptions_household_idx ON subscriptions (household_id);

-- Insurance policies (auto, home, health). Policy numbers stored as last 4 only.
CREATE TABLE insurance_policies (
    id                  BIGSERIAL PRIMARY KEY,
    household_id        BIGINT      NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    policy_type         TEXT        NOT NULL CHECK (policy_type IN ('AUTO', 'HOME', 'HEALTH', 'LIFE', 'OTHER')),
    carrier             TEXT        NOT NULL,
    policy_number_last4 CHAR(4)     CHECK (policy_number_last4 IS NULL OR policy_number_last4 SIMILAR TO '[0-9A-Za-z]{4}'),
    premium_amount      NUMERIC(12, 2) CHECK (premium_amount >= 0),
    premium_cadence     TEXT        CHECK (premium_cadence IN ('MONTHLY', 'SEMIANNUAL', 'ANNUAL')),
    renewal_on          DATE,
    document_id         BIGINT      REFERENCES documents (id) ON DELETE SET NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX insurance_policies_household_renewal_idx ON insurance_policies (household_id, renewal_on);

-- Health explanations of benefits and medical bills.
CREATE TABLE health_claims (
    id                      BIGSERIAL PRIMARY KEY,
    household_id            BIGINT      NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    member_user_id          BIGINT      REFERENCES app_users (id) ON DELETE SET NULL,
    provider                TEXT        NOT NULL,
    service_date            DATE,
    billed_amount           NUMERIC(12, 2) CHECK (billed_amount >= 0),
    insurance_paid          NUMERIC(12, 2) CHECK (insurance_paid >= 0),
    patient_responsibility  NUMERIC(12, 2) CHECK (patient_responsibility >= 0),
    status                  TEXT        NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'PAID', 'DISPUTED')),
    document_id             BIGINT      REFERENCES documents (id) ON DELETE SET NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX health_claims_household_idx ON health_claims (household_id, status);

-- Gmail billing confirmations: metadata only (message ID, sender domain, extracted amount/vendor).
CREATE TABLE email_confirmations (
    id                      BIGSERIAL PRIMARY KEY,
    household_id            BIGINT      NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    gmail_message_id        TEXT        NOT NULL,
    received_at             TIMESTAMPTZ NOT NULL,
    sender_domain           TEXT        NOT NULL,
    vendor                  TEXT,
    amount                  NUMERIC(12, 2) CHECK (amount >= 0),
    matched_transaction_id  BIGINT      REFERENCES transactions (id) ON DELETE SET NULL,
    UNIQUE (household_id, gmail_message_id)
);

-- Anomaly flags raised by detection rules; a person reviews them.
CREATE TABLE anomaly_flags (
    id                      BIGSERIAL PRIMARY KEY,
    household_id            BIGINT      NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    kind                    TEXT        NOT NULL CHECK (kind IN (
                                'DUPLICATE', 'MISSING_EMAIL', 'MISSING_STATEMENT', 'PRICE_CHANGE',
                                'NEW_VENDOR', 'OUTLIER', 'ORPHANED_AUTOPAY')),
    severity                TEXT        NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH')),
    reason                  TEXT        NOT NULL,
    transaction_id          BIGINT      REFERENCES transactions (id) ON DELETE CASCADE,
    subscription_id         BIGINT      REFERENCES subscriptions (id) ON DELETE CASCADE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_at             TIMESTAMPTZ,
    reviewed_by_user_id     BIGINT      REFERENCES app_users (id) ON DELETE SET NULL
);
CREATE INDEX anomaly_flags_open_idx ON anomaly_flags (household_id, created_at) WHERE reviewed_at IS NULL;

-- Audit trail: who did what, when. Never stores the content itself.
CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    household_id BIGINT     NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    user_id     BIGINT      REFERENCES app_users (id) ON DELETE SET NULL,
    action      TEXT        NOT NULL,          -- e.g. 'DOCUMENT_UPLOADED', 'TRANSACTION_EDITED'
    entity      TEXT        NOT NULL,          -- table or aggregate name
    entity_id   BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX audit_log_household_time_idx ON audit_log (household_id, created_at);
