# CLOUD-05 — Managed Persistence / Secrets / Storage

**Status: PLAN-ONLY. No GCP resource has been created. No `terraform apply` has been run.**

This stage moves DEV Backend from `PFI_PERSISTENCE_MODE=memory` to a real, managed Cloud SQL for
PostgreSQL instance, adds Secret Manager for the two secrets the Backend actually needs
(`DATABASE_URL`, `PFI_AUTH_JWT_SECRET`), and audits (without acting on) the existing Cloud Storage
bucket for future model-artifact usage. Everything in this document describes the *design* that a
later, separately authorized apply would create — see `CLOUD_05_CHANGE_PLAN.md` for the actual
`terraform plan` output this design produces today.

## Why now

DEV has run entirely on `PFI_PERSISTENCE_MODE=memory` since CLOUD-04 (`InMemoryStudyRepository`) —
correct for a first smoke-tested deployment, but no data survives a restart or new revision. CLOUD-05
is the stage that gives DEV real, restart-surviving persistence without introducing PROD-grade cost
or complexity the thesis project does not need (see `ADR-006` in `CLOUD_ROADMAP.md`: no PROD
environment exists).

## What changes, and what deliberately does not

| Changes | Does not change |
|---|---|
| `PFI_PERSISTENCE_MODE`: `memory` → `postgres` (Backend Cloud Run env var) | Backend image — same immutable digest `backend:dee0826c9e1121e348192058acde841b6f933608`, confirmed compatible without a rebuild (see "Image compatibility" below) |
| New Cloud SQL for PostgreSQL instance (`pfi-postgres-dev`) + `pfi` database | Frontend — no Terraform diff, no redeploy |
| New Cloud SQL Auth Proxy sidecar in the Backend's Cloud Run service | AI service — untouched, `enable_ai_service` stays `false` |
| Two new Secret Manager secret containers (`pfi-dev-database-url`, `pfi-dev-jwt-secret`) | Artifact Registry, WIF, CI pushers — untouched |
| `backend_runtime` SA gains `roles/cloudsql.client` (project-scoped, unavoidable for the connector) and `roles/secretmanager.secretAccessor` on exactly those 2 secrets (resource-scoped) | The existing `pfi-rm-lumbar-artifacts-2026-ef` bucket — audited only, no new bucket, no IAM grant this stage |

## Image compatibility (verified, not assumed)

Audited `pom.xml`: the Backend depends on plain `org.postgresql:postgresql:42.7.4` (main) and
`org.testcontainers:postgresql:1.20.4` (test only) — no Flyway, no HikariCP, no
`com.google.cloud.sql:postgres-socket-factory`. `PostgresStudyRepository.toJdbcUrl()` builds a plain
`jdbc:postgresql://<host>:<port><path>?sslmode=require&user=...&password=...` URL — it does not
understand Cloud Run's native Unix-socket Cloud SQL integration (which requires the socket-factory
dependency to be on the classpath). It **does** understand plain TCP to any host:port, which is
exactly what the Cloud SQL Auth Proxy sidecar exposes on `127.0.0.1:5432` inside the same Cloud Run
revision. Conclusion: **no rebuild is required** — the already-published image already supports
Postgres over TCP; only runtime configuration (env vars + a sidecar container) changes.

## Migration validation (done, without touching GCP)

All 17 Backend migrations (`docs/migrations/*.sql`, applied alphabetically by `SqlMigrationRunner`)
were run end-to-end against a clean, throwaway `postgres:16-alpine` container on a private Docker
network, using the exact already-published Backend image — no rebuild, no real GCP resources
touched. Verified: migrations apply cleanly in order, tracked correctly in `schema_migrations`,
re-running the same image against the same already-migrated database is idempotent (no duplicate
application, no error), and a synthetic row written before a container restart is still present
after — confirming the intended persistence-across-restart behavior this stage exists to deliver.

## Networking decision

Cloud SQL Auth Proxy as a **sidecar container in the same Cloud Run v2 service** — not Cloud Run's
native Unix-socket integration (would need the socket-factory dependency, forcing a rebuild), not a
VPC + Private Service Access connection (real ongoing complexity not justified at this scale), and
not a raw public-IP TCP connection authorized by IP (explicitly forbidden by the user's own
constraint: no `authorized_networks`, no `0.0.0.0/0`). The proxy authenticates to Cloud SQL using the
Backend's own runtime service account (`roles/cloudsql.client`) and exposes plain TCP the existing,
unmodified JDBC driver already speaks. See `CLOUD_05_CLOUD_SQL_DESIGN.md` for the full reasoning.

## Documents in this set

- `CLOUD_05_CLOUD_SQL_DESIGN.md` — instance sizing, sourced pricing, backup/HA/teardown strategy.
- `CLOUD_05_SECRET_MANAGER.md` — secret design and the exact out-of-band commands to populate values.
- `CLOUD_05_STORAGE_DESIGN.md` — existing bucket audit and future usage design (no IAM grants yet).
- `CLOUD_05_COST_CHECK.md` — the cost hard-gate table.
- `CLOUD_05_CHANGE_PLAN.md` — both `terraform plan` runs (flag off / flag on) and their diffs.
- `CLOUD_05_SECURITY_REVIEW.md` — the security checklist and its verdicts.

## Explicitly out of scope this pass

No API enablement (`sqladmin.googleapis.com`, `secretmanager.googleapis.com` remain disabled), no
resource creation, no secret version, no IAM write, no Backend Cloud Run revision update, no bucket
IAM grant, no `terraform apply`. See `CLOUD_05_CHANGE_PLAN.md` for the exact, reviewed diff a future
authorized apply would produce.
