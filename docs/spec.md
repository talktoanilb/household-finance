# Household Financial Tracker: Phase 1 Spec

Status: draft for review. Scope: financial tracking, subscriptions, insurance, health reports, email integration, household dashboard.

## 1. Purpose

A shared household tool where you and your wife upload bills (photos or manual entry), link bank and credit card statements, and monitor subscriptions and insurance. The app extracts charges, categorizes spending, flags anomalies and shows a weekly digest: earnings, where the money went, what can be cut, and what needs attention.

Secondary goal: a finished, tested portfolio project using Java/Spring, MCP, LangGraph, RAG, PostgreSQL, photo OCR, and spec-driven AI-assisted development.

## 2. Non-goals (v1)

- No investment tracking, crypto, or brokerage accounts.
- No WhatsApp, Telegram, or family group-chat monitoring.
- No budgeting advice or spending recommendations.
- No real-time price checking or alerts (weekly digest only).
- No bill pay or autopay setup (read-only).
- No mobile app yet (web only).

## 3. Users and key questions

Users: Household head + spouse. Shared visibility into all spending.

1. "How much did we spend this week, and on what?"
2. "What subscriptions are still active and charging us?"
3. "Are there duplicate charges or charges I don't recognize?"
4. "Did a billing email arrive but not show up on the statement yet?"
5. "What insurance are we paying for, and what benefits do we have?"
6. "What's going on with health claims or medical bills?"
7. "What can we cut to save money?"

## 4. Inputs

**Bills (by either household member):**
- Photo upload with OCR to extract vendor, amount, date, category.
- Or manual entry: vendor, amount, date, category, payment method.

**Financial data:**
- Bank statements as CSV (exported from Chase, Citi, etc.).
- Credit card statements as CSV.
- Plaid integration (optional, for auto-sync later).

**Email monitoring:**
- Billing confirmations, charge alerts, subscription renewals from Gmail.
- Parsed automatically if Gmail is connected.

**Insurance documents:**
- PDF upload of insurance policies (homeowner, car, health).
- Agent extracts coverage limits, deductibles, benefits, claim procedures.

**Health reports:**
- EOB (explanation of benefits) PDFs from insurance.
- Medical bills uploaded by photo or PDF.

**Account setup:**
- Both users create accounts with email.
- Household linked (one URL, shared data).

## 5. Core logic

### Transactions

Each transaction has: date, vendor, amount, category, payment method (card ending in X, bank account), source (photo, manual, statement, email), status (reconciled, pending, flagged).

Categories (auto-detect, user-editable): groceries, utilities, insurance, subscriptions, health, transport, home, dining, entertainment, other.

### Subscriptions

Extract from statements (recurring monthly/annual charges) and from confirmation emails. Track: vendor name, amount, renewal date, payment method. Flag: canceled but still charging, duplicate services (Netflix twice?), trials that convert to paid.

### Anomalies

Flag if:
- Charge appears on statement but no matching email confirmation.
- Email confirms a charge that never appears on statement (pending, may be declined).
- Same charge appears twice on the same card (duplicate).
- Charge amount differs from expected (subscription raised price?).
- Charge vendor not seen before in 6+ months (new vendor, typo?).
- High outlier in a category (groceries $500 this week, usually $150?).

### Insurance and benefits

Store: policy PDFs, coverage limits, deductibles, benefits summary. Extract dates (renewal, claim deadlines). Alert if renewal is 30 days away.

### Health reports

Parse EOB PDFs: date of service, provider, charge, what insurance paid, your responsibility. Track medical bills separately. Alert if bill amount doesn't match EOB after 30 days.

### Weekly digest

Email or in-app summary:
- Total earnings this week (salary, investments, other).
- Total spending by category.
- Week-over-week change.
- Flagged anomalies (in priority order).
- Subscriptions due to renew.
- Insurance renewals coming up.
- Outstanding medical bills (unpaid > 30 days).
- Top 3 things you can cut (unused subscriptions, high-spend categories).

## 6. Architecture

Three services plus a web UI and email delivery.

### Core service (Java 21, Spring Boot)

- Parser library: OCR, statement parsing (CSV), email extraction, PDF extraction.
- Business logic: categorization, anomaly detection, subscription tracking.
- REST API and MCP server.
- PostgreSQL for transactions, users, subscriptions, policies.
- pgvector for RAG (spending history patterns, known vendors).

### Agent service (Python, LangGraph)

- Calls core service only through MCP tools. No business logic.
- RAG over transaction history to categorize new charges.
- Extracts structured data from policy PDFs (coverage, deductibles).
- Drafts the weekly digest in plain English.
- Checks for anomalies and flags unusual patterns.

### Email service

- Sends weekly digests, anomaly alerts (immediate for high-priority).
- Subscription renewal reminders (7 days before).
- Insurance renewal alerts (30 days before).

### UI (React, single-page app)

- **Dashboard:** spending this week, by category, vs. earnings.
- **Upload bill:** camera (mobile later) or file picker, confirm OCR result, categorize.
- **Transactions table:** filter by date, category, payment method; mark as reconciled; edit if wrong.
- **Subscriptions:** list, renewal dates, payment method, option to mark as canceled.
- **Insurance:** list policies, coverage, renewal dates, claim procedures.
- **Health:** EOBs and medical bills, what's pending vs. paid.
- **Alerts/anomalies:** list of flagged items, mark as reviewed.
- **Settings:** linked accounts (Gmail, statements), users in household, preferences.

## 7. MCP tools (core service)

| Tool | Input | Output |
|---|---|---|
| `upload_bill_photo` | image file, user | OCR text, vendor, amount, date, category (suggested) |
| `create_transaction` | vendor, amount, date, category, method | transaction ID, status |
| `parse_statement` | CSV file | transactions extracted, reconciled with existing |
| `get_subscriptions` | date range | active subscriptions, renewal dates, amounts |
| `flag_anomalies` | date range | list of flagged transactions with reason and priority |
| `extract_policy_info` | PDF | coverage summary, limits, deductibles, renewal date |
| `extract_eob_info` | PDF | claims, service dates, charges, insurance paid, patient responsibility |
| `generate_digest` | date range | weekly digest summary (plain text or HTML) |
| `get_spending_by_category` | date range | total and breakdown by category |

## 8. Agent graph (LangGraph)

1. `parse_bill_photo`: extract vendor, amount, date from OCR; ask for missing fields.
2. `suggest_category`: RAG lookup over spending history to recommend a category; user confirms.
3. `extract_statement`: parse CSV, match to existing transactions, flag new ones.
4. `extract_email`: Gmail polling for billing confirmations, subscription alerts.
5. `extract_policy_pdf`: parse insurance policy, extract terms, coverage, renewal.
6. `extract_eob_pdf`: parse health EOB, extract claims and patient responsibility.
7. `detect_anomalies`: check for duplicates, mismatches, outliers, new vendors.
8. `draft_digest`: compile weekly summary, prioritize alerts, explain in plain English.
9. `respond`: send to both users via email or show in dashboard.

## 9. Safety and legal posture

- **Privacy:** All data stored in your own database. No sharing with third parties. Encrypted passwords, secure session handling.
- **For both users:** Clear data-sharing agreement (husband and wife own the household data together).
- **No financial advice:** Tool shows data and flags anomalies. Never recommends cutting a subscription or paying a bill early.
- **OCR limits:** Photos must be readable (not too blurry); manual entry is fallback.
- **PDF parsing:** Insurance PDFs vary; extraction may fail on unusual formats. Manual review required.
- **Plaid (later):** If added, use OAuth; don't store credentials.

## 10. Testing

- **Golden tests (30+):** Hand-checked transaction categorizations, anomaly detection (duplicate, outlier, new vendor), subscription extraction from statements, digest formatting.
- **OCR tests:** Real bill photos (utility, grocery, insurance) checked against manual entry.
- **Statement parse tests:** Chase, Citi, Bank of America CSVs parsed correctly; transactions matched to existing data.
- **Email extraction:** Billing confirmations and alerts parsed and matched to transactions.
- **Property tests:** Spending totals are correct; anomaly flags are always for valid reasons; digest includes all categories.
- **Agent evaluation:** 40 user scenarios (upload bill, link statement, flag anomaly, etc.) with checks for correctness and no unsolicited advice.

## 11. Milestones (sequence, not dates)

- **M0:** repo, this spec, CLAUDE.md, CI skeleton, PostgreSQL schema.
- **M1:** OCR and statement parsing (CSV), hand-tested extraction from real bills and statements.
- **M2:** Java business logic (categorization, anomaly detection, subscription tracking).
- **M3:** REST API and MCP server over the logic.
- **M4:** Python LangGraph agent calling MCP tools, RAG over transaction history.
- **M5:** PDF extraction (insurance, EOB).
- **M6:** Email polling (Gmail integration for billing confirmations).
- **M7:** Weekly digest generation and email delivery.
- **M8:** React UI (dashboard, upload, transactions, subscriptions, settings).
- **M9:** AWS deployment, GitHub Actions CI, README, demo video.

Definition of done for v1: live app, both of you uploading bills and seeing the dashboard, passing tests, README and demo video ready.

## 12. Risks

- **OCR failures:** Blurry photos or unusual formats won't parse. Mitigated by fallback manual entry.
- **Statement format drift:** If your bank changes CSV format, parsing breaks. Mitigated by monitoring real exports and updating parser.
- **Privacy:** Storing household financial data is sensitive. Mitigated by local database, encryption, no third-party sharing.
- **Scope creep:** Insurance and health PDFs can be complex. Mitigated by accepting "best effort" extraction and flagging manual review needed.
- **Scope creep (again):** Phase 2 (WhatsApp, group chats, etc.) stays out until v1 ships.

## 13. Open decisions

- UI framework: React with Vite vs. Next.js (simpler auth, deployment).
- Email delivery: SendGrid, AWS SES, or Gmail API (forwarding to yourself)?
- Deployment: AWS (EC2 + RDS), Railway, or Heroku?
- OCR library: Tesseract (free, open source) or Claude's vision (paid, better).
- Plaid integration timeline: v1 or v2?

## 14. Draft CLAUDE.md for the repo

- Read this spec before any change; do not add scope outside Section 2.
- Anomaly detection is conservative: flag only if confident. False negatives are better than false positives for a personal finance tool.
- No financial advice. Never suggest cutting a subscription or paying early. Flag it and let the user decide.
- All money uses BigDecimal. No floating point.
- RAG corpus is the user's own transaction history, categorized. No third-party spending data.
- Both household members own the data equally. No privacy asymmetry.
- Small commits, one milestone at a time, tests first.
- Plaid credentials are never stored. OAuth only.
