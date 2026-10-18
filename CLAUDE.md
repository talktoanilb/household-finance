# Household Finance Tracker: instructions for Claude Code

Read `docs/spec.md` before any change. It is the source of truth for scope.

## Scope
- Do not add scope outside Section 2 (non-goals) of the spec. Phase 2 (WhatsApp, group chats, family alerts) stays out until v1 ships.
- Build one milestone at a time, in the order listed in Section 11 of the spec.

## Money
- All money uses `BigDecimal` in Java and `Decimal` in Python. Never use `double` or `float` for currency.
- Database money columns are `NUMERIC(12,2)`. Round only at presentation.

## Where logic lives
- Parsing, categorization and anomaly math live only in the Java core.
- The Python agent calls core functionality through MCP tools. It must not compute totals, matches or flags itself.

## Anomaly detection
- Be conservative: flag only when there is a clear reason. False negatives are better than false positives for a personal finance tool.
- Every flag must include a human-readable `reason`.

## Advice and tone
- No financial advice. Never suggest cancelling a subscription, paying early, or moving money. Flag the fact and let the user decide.
- Never recommend specific financial products.

## Privacy and security (non-negotiable)
- Store only last 4 digits of card, account and policy numbers. Never store full numbers.
- Store files in encrypted storage; the database holds only a storage key, hash and OCR text.
- Email integration stores Gmail message IDs and extracted metadata only. Never store message bodies.
- Never log document contents, OCR text, account numbers, or OAuth tokens. Audit logs record who did what and when, not the content.
- Secrets never go in the repo or `application.yml`. Use environment variables locally and AWS Secrets Manager in production.
- Plaid and Gmail credentials: OAuth only. Refresh tokens stored encrypted, never logged.
- No third-party logging or analytics services.

## Data
- The RAG corpus is the household's own categorized transaction history. No third-party spending data.
- Both household members own the data equally. No per-user privacy asymmetry.
- Tests and demos use synthetic data only. Never commit real statements, bills, emails or photos.

## Database
- Schema changes go in new Flyway migrations (`core/src/main/resources/db/migration/`). Never edit an applied migration.

## Workflow
- Small commits, one milestone at a time, tests first.
- Each golden test for a calculation is hand-checked and documented in the test itself.
