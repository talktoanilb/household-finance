# Household Finance Tracker

A shared household tool for spending, subscriptions, insurance and health claims. Bills arrive as photos or manual entries, statements and billing emails are matched to them, and a weekly digest shows earnings, spending, anomalies and renewals.

Status: **M0** done, **M1** in progress (M1a Chase/Citi CSV parsers done; M1b ingestion foundation built, pending your Maven run; M1c learned CSV layouts next). Specification: [`docs/spec.md`](docs/spec.md).

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

cd agent && pip install -r requirements-dev.txt && pytest
```

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
- Not built yet: learned layouts for other banks (M1c), PDF statements and bill photos (M1d), re-verification after an amount correction, pending-to-cleared updates on re-import, and any REST endpoint or screen for answering questions (M3/M8).

## Verification status (M0)

- Schema: `V1__init_schema.sql` applied to PostgreSQL 16. Constraints checked: full card numbers rejected, duplicate statement lines rejected, negative amounts rejected.
- Core: Maven build and Spring context test are defined and run in CI. Not yet run end-to-end in this environment.
- Agent: placeholder package test passes; pytest runs in CI.
