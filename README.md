# Household Finance Tracker

A shared household tool for spending, subscriptions, insurance and health claims. Bills arrive as photos or manual entries, statements and billing emails are matched to them, and a weekly digest shows earnings, spending, anomalies and renewals.

Status: **M0** done, **M1** mostly done (M1a CSV parsers, M1b ingestion foundation, M1c learned CSV layouts, M1d Chase PDF statements, M1f bill and receipt photos). Still open in M1: PDF layouts for other banks, scanned and PDF bills, and the optional AI reading. A thin REST layer and one upload page (bill and receipt photos, plus answering statement questions) now run locally; there is no login or full UI yet (M3/M8). Specification: [`docs/spec.md`](docs/spec.md).

## Layout

| Path | Contents |
|---|---|
| `core/` | Java 21, Spring Boot, Flyway. Business logic, persistence, REST and MCP (later). |
| `core/src/main/resources/db/migration/` | Versioned PostgreSQL migrations. |
| `agent/` | Python, LangGraph. Calls core through MCP tools only (M4). |
| `docs/spec.md` | Phase 1 specification. |
| `CLAUDE.md` | Rules for AI-assisted development in this repo. |
| `docker-compose.yml` | Local PostgreSQL (pgvector) for development. |
| `.github/workflows/ci.yml` | CI: Java build and tests against PostgreSQL, Python tests. |

## Run locally

```bash
docker compose up -d db                 # PostgreSQL on 127.0.0.1:5432

cd core && mvn verify                   # builds, applies all migrations, runs the context test
# Bill and receipt photos need the Tesseract program installed: brew install tesseract (Mac) or apt-get install tesseract-ocr

cd agent && pip install -r requirements-dev.txt && pytest
```

### Run the app (local only)

There is no login yet, so the app acts as the first household member in the database and listens on `127.0.0.1` only (set `BIND_ADDRESS` to change that, and do not on a shared network). Create that member once:

```sql
-- docker compose exec db psql -U household -d household
INSERT INTO households (name) VALUES ('Home');
INSERT INTO app_users (household_id, email, display_name, password_hash)
VALUES ((SELECT max(id) FROM households), 'you@example.com', 'You', 'not-a-login-yet');
```

Then `cd core && mvn spring-boot:run` and open <http://localhost:8080/>. Pick a bill or receipt photo, check the values against the paper and press Save. Endpoints: `POST /api/documents` (upload), `POST /api/documents/{id}/confirm`, `POST /api/documents/{id}/cancel`, `GET /api/categories`, `GET /api/questions`, `POST /api/questions/{id}/answer`, `POST /api/imports/{id}/cancel`. Everything is checked again in Java on confirm; the upload step stores nothing in `transactions`.

Local credentials in `docker-compose.yml` and `application.yml` are for development only. Production secrets come from AWS Secrets Manager.

## Decisions so far

- **UI:** React with Vite (M8). Not started.
- **OCR and extraction:** Hybrid. Tesseract and PDF text run locally by default. AI extraction is opt-in (off by default) for hard layouts, because documents would leave the machine. Anything unclear is asked of the user, never guessed. Manual entry is the fallback.
- **Deployment:** Deferred to M9. Local Docker Compose until then.
- **Plaid:** Not in v1. CSV statement upload first.

## M1 progress

- `core/.../statement/`: `ChaseCheckingParser` and `CitiCostcoVisaParser` turn bank CSVs into `StatementLine`s with an idempotency fingerprint (`external_ref`). Strict header check, 2-decimal money, no file contents in error messages.
- `V2__external_ref_uniqueness_without_account.sql`: fixes duplicate imports when `account_id` is NULL.
- **M1b** (`core/.../ingest/`): `ImportService` stages parsed lines, `Verifier` checks them in Java (running balance in either sort order, implausible dates), and anything unclear becomes a question for the user. Nothing reaches `transactions` until every question is answered; confirm, exclude a line, or correct an amount. Re-imports skip lines already saved, and the same file cannot be imported twice. Tables are in `V3__ingestion_staging.sql`.
- **Chase checking PDF statements** (`ChasePdfStatementParser`, `ChasePdfImporter`): text is extracted locally with Apache PDFBox. The statement is checked against its own printed totals (beginning balance + every line = ending balance, deposits total, first-line balance), against the account's last 4 digits, and against the previous statement for the same account (a missing month or a balance that does not join up becomes a question). Only the last 4 digits of the account number are kept. Scanned PDFs are not supported yet and are refused with a clear message.
- To check your own real statements without committing them: `REAL_STATEMENT_PDF="/path/one.pdf,/path/two.pdf" mvn -Dtest=RealStatementPdfTest test`. It prints only counts. Never commit real statements.
- **M1c** (V6): learned CSV layouts for other banks. A layout is saved once the user has confirmed it and is verified again on every import.
- **M1f: bill and receipt photos** (`core/.../document/`, V7 `document_imports`): a photo is read locally by the Tesseract program (run from Java, no AI service). `DocumentExtractor` proposes vendor, amount and date; `DocumentVerifier` checks them in Java (subtotal + tax = total, plausible dates, 2-decimal amounts); nothing is saved until the user confirms. Bills are saved as pending expenses dated on the due date, receipts as posted expenses on the purchase date. Only a hash and the proposed values are kept; the photo and its text are not stored.
  - Sideways and upside-down photos are rotated automatically; oversized photos are scaled down; photos with no readable amount or date are refused with nothing saved.
  - Each photo is read at three sizes. If the readings disagree on a total, subtotal, tax or date, no value is proposed and the note lists the candidates. A total that fits no subtotal-plus-tax reading is not proposed either.
  - Known limit: a digit misread that every reading repeats cannot be caught (for example 7.57 read as 7.67 on small thermal print). The user checks the proposal against the paper before confirming. Scans (for example from CamScanner, exported as JPG) read far better than camera photos.
  - To check your own photos without committing them: `HF_PHOTOS=/path/to/folder mvn -Dtest=RealPhotosCheckTest test`. It prints only amounts, dates and notes (`HF_SHOW_TEXT=<n>` prints a masked copy of photo n's text). Never commit real receipts.
- Not built yet: PDF layouts for other banks, scanned PDFs and PDF bills, the optional AI reading, choosing between candidate amounts (needs the UI), re-verification after an amount correction, pending-to-cleared updates on re-import, and a real login, statement upload through the web (needs an account picker), and the full UI (M3/M8).

## Verification status (M0)

- Schema: `V1__init_schema.sql` applied to PostgreSQL 16. Constraints checked: full card numbers rejected, duplicate statement lines rejected, negative amounts rejected.
- Core: Maven build and Spring context test are defined and run in CI. Not yet run end-to-end in this environment.
- Agent: placeholder package test passes; pytest runs in CI.
