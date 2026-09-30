# CLOUD-07 — Observability

**Status: DESIGN ONLY.** No dashboard, no log-based metric, no custom instrumentation was created.
Cloud Logging and Cloud Monitoring APIs are already `ENABLED` on the project (confirmed read-only —
Cloud Run enables both automatically for any deployed service; this is not a CLOUD-07 action) but
zero notification channels, alert policies, uptime checks, or dashboards exist (confirmed via the
Monitoring REST API, all empty).

## Phase 5 — Minimum observability signals

| Component | Signal | Built-in or custom? | Source |
|---|---|---|---|
| Backend | Request count | Built-in | `run.googleapis.com/request_count` |
| Backend | 5xx rate | Built-in | Same metric, filtered `response_code_class="5xx"` |
| Backend | Latency (p50/p95) | Built-in | `run.googleapis.com/request_latencies` |
| Backend | Instance startup failures | Built-in | `run.googleapis.com/container/startup_latencies` + Cloud Run's own deploy-status API (a revision that never becomes `Ready`) |
| Backend | Container crashes | Built-in (indirect) | Covered by the uptime check (Signal D, `CLOUD_07_ALERTING.md`) — a crash-looping container simply fails the health check, no separate "crash" metric needed |
| Backend | Auth failures | **Not yet instrumented** — see "Gaps" below | Would need an application-level counter; AuthFilter's rejections aren't currently exposed as a Cloud Monitoring metric |
| Frontend | Request count, 5xx rate | Built-in | Same `run.googleapis.com/request_count`, `service_name="pfi-frontend-*"` |
| Frontend | Startup failure | Built-in | Same deploy-status mechanism as Backend |
| Frontend | Health availability | Built-in (via uptime check) | The uptime check hits Backend today (the service with an actual health endpoint); Frontend's root `/` could get its own uptime check later if warranted |
| Cloud SQL (future, not created) | Availability, connections, CPU, memory, storage, restart/failure | Built-in, once the instance exists | `cloudsql.googleapis.com/database/*` metrics — no code change needed, but meaningless before CLOUD-05 is ever applied |

**No custom metric is proposed.** Every signal above (except auth failures) is already exposed by
Cloud Run's or Cloud SQL's built-in metrics with zero extra instrumentation — creating a custom
log-based metric would be the first thing in this project's whole Monitoring footprint with an
ingestion cost (see `CLOUD_07_COST_CHECK.md`), and none is justified while built-ins already cover
the signal.

### Gap: auth failures

`AuthFilter`'s JWT rejections are not currently counted anywhere Cloud Monitoring can alert on —
they show up only as unstructured log lines. Closing this gap would need either (a) a log-based
count metric filtered on a recognizable log message (cheap, no app code change, but fragile if the
log message text ever changes) or (b) an application-level Micrometer counter exposed as a custom
metric (more robust, but is an app code change outside CLOUD-07's infra-only scope). Documented as a
known gap, not fixed this stage.

## Phase 6 — Logging design

### Current state (audited)

Backend uses Spring Boot's default logger (no `logback-spring.xml`, no JSON encoder found in
`src/main/resources`) — logs are plain text to stdout, which Cloud Run's logging agent captures and
best-effort parses for severity, but without the structured fields below. **No structured logging
exists today.**

### Minimum fields for a future structured log line

| Field | Source |
|---|---|
| `traceId` | Cloud Run/Cloud Trace already injects `X-Cloud-Trace-Context`; propagating it into the app's log context (e.g. via an MDC filter) is the only piece not automatic |
| `requestId` | Generate per-request (e.g. `UUID` in a servlet filter) if not already present |
| `service` | Static per deployment (`pfi-backend`, `pfi-frontend`) |
| `environment` | Static per deployment (`dev`, `demo`) — already available as `PFI_PERSISTENCE_MODE`'s sibling env vars would need a matching `PFI_ENVIRONMENT` var, not currently set |
| `revision` | `K_REVISION` — a Cloud Run-injected env var, already available with no code change |
| `status` | HTTP status code of the response |
| `latency` | Request duration |
| `principal`/`role` | The authenticated doctor's identity/role, when present — **never the raw JWT** |
| `errorCategory` | A coarse classification (`AUTH`, `VALIDATION`, `AI_TIMEOUT`, `DB`, `UNKNOWN`) for exceptions, not a raw stack trace in a structured field |

Implementing this (a logback JSON encoder + a request-scoped MDC filter) is an **application code
change**, outside CLOUD-07's infra-only, plan-only scope — documented here as the target shape, not
implemented. Cloud Logging works today without it (plain-text ingestion), just without these fields
queryable/filterable.

### Redaction policy — never logged, at any log level

- JWT (the token itself — `principal`/`role` derived from it is fine, the raw token is not)
- Password (there is no password field in the Backend's own request bodies today, but the policy
  holds for the future Cloud SQL credential path too — see `CLOUD_05_SECRET_MANAGER.md`)
- Any Secret Manager-sourced value (`DATABASE_URL`, `PFI_AUTH_JWT_SECRET`)
- DICOM PHI / patient identifiers / sensitive UIDs
- Raw medical request bodies (study payloads, findings content) — log metadata about a request
  (path, status, latency, study ID *if* that ID is itself not PHI — needs its own confirmation before
  ever logging it) never the body

This mirrors the same posture already established for Secret Manager (`CLOUD_05_SECRET_MANAGER.md`:
never print a secret in shell/report) and extends it to the two new categories relevant to a medical
imaging product: PHI and raw clinical payload content.

## Phase 12 — Dashboard design (not created)

**"PFI Lumbar MRI — DEV/DEMO"**, proposed panels:

1. Backend request rate (`run.googleapis.com/request_count`, grouped by `service_name`)
2. Backend error rate (same metric, filtered `5xx`)
3. Backend latency (p50/p95, `run.googleapis.com/request_latencies`)
4. Frontend request/error rate (same two metrics, `pfi-frontend-*`)
5. Active revisions per service (which revision(s) are currently receiving traffic — directly useful
   during a rollback evaluation)
6. Instance count (`run.googleapis.com/container/instance_count`) — visualizes scale-to-zero
   behavior and confirms `min_instances` settings are taking effect as expected
7. DB health (future — `cloudsql.googleapis.com/database/*`, blank/absent panels until Cloud SQL
   exists, not an error state)
8. Deployment/revision timeline — when each revision was created, correlated against the release
   manifests (`pfi-cloud-release.v1`) that produced them

A real `google_monitoring_dashboard` resource is a small, mechanical addition to `modules/monitoring`
once these panels are reviewed — deliberately not added to Terraform yet to keep CLOUD-07's plan
preview (`CLOUD_07_CHANGE_PLAN.md`) minimal and predictable (alert policies + uptime check only).
