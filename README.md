# Household Finance Tracker

A shared household tool for spending, subscriptions, insurance and health claims. Bills arrive as photos or manual entries, statements and billing emails are matched to them, and a weekly digest shows earnings, spending, anomalies and renewals.

Status: **M0** (foundation). Specification: [`docs/spec.md`](docs/spec.md).

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
- **OCR:** Tesseract, run locally, so bill photos are not sent to a third party. Claude vision was rejected for v1 for this reason. Manual entry is the fallback.
- **Deployment:** Deferred to M9. Local Docker Compose until then.
- **Plaid:** Not in v1. CSV statement upload first.

## Verification status (M0)

- Schema: `V1__init_schema.sql` applied to PostgreSQL 16. Constraints checked: full card numbers rejected, duplicate statement lines rejected, negative amounts rejected.
- Core: Maven build and Spring context test are defined and run in CI. Not yet run end-to-end in this environment.
- Agent: placeholder package test passes; pytest runs in CI.
