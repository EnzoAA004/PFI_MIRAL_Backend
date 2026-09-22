# Cloud Stage 4I — Backend Green Service Deployment (2026-09-22)

## Architectural reason for blue/green replacement

Stages 4G and 4H exhausted every plausible application-side and
config-side explanation for `pfi-backend-dev`'s instability: connection
pooling was validated end-to-end (1171/1171 unit tests, real Cloud SQL
smoke), a dedicated revision-level `min-instances=1` was proven to keep
an instance genuinely warm and idle-stable for 4+ minutes, and yet the
same instance was torn down and restarted on nearly every real request
the moment it received traffic through the service's **percentage-split**
serving path — while the identical revision worked flawlessly via
`traffic-tag` routing, local Docker, and isolated single requests. The
common factor across every failure was specifically Cloud Run's
percentage-split traffic mechanism on this one service, not the
application. Rather than continue debugging that platform-level path,
the decision was to stop using percentage splits on `pfi-backend-dev`
entirely and deploy a **new, independent Cloud Run service**
(`pfi-backend-v2-dev`) that only ever has one serving revision at 100% —
avoiding the buggy mechanism structurally instead of working around it.

## 1. Legacy service state — untouched throughout

`pfi-backend-dev` / `pfi-backend-dev-00002-jwd` remained at 100% traffic
for the entire duration of this stage, confirmed via
`gcloud run services describe --format="value(status.traffic)"` both
before and after. One health check during the experiment (Step 19)
returned a client-side timeout; an immediate retry returned `200
{"status":"ok"}` — consistent with the pre-existing, already-documented
legacy routing behavior from Stages 4G/4H, not something this stage
caused (traffic config was never modified).

## 2. Green service

- **Name**: `pfi-backend-v2-dev`
- **Revision**: `pfi-backend-v2-dev-00001-zx5` (the service's only
  revision — no splits, no tags)
- **URL**: `https://pfi-backend-v2-dev-810538429027.us-central1.run.app`

## 3. Image (no rebuild)

Verified independently in Artifact Registry before deploying (not
assumed from the instruction alone):

- Repository: `pfi-containers`
- Tag: `backend-cloud-diagnostics-0a4f084`
- Digest: `sha256:4a6e404817e068bc568a583294ce9723d72b144e64f74d472e5884db0fa2e80d`
- Size: 465 MB
- Corresponds to commit `0a4f084` in the Backend repo's git history
  (confirmed via `git log`), one commit after `e6f51dc chore: add
  Backend Cloud runtime diagnostics` — the source of the
  `/api/system/runtime-diagnostics` endpoint and `X-PFI-Revision`
  header used throughout this stage's evidence.

## 4. Deploy configuration

```
gcloud run deploy pfi-backend-v2-dev \
  --image=...@sha256:4a6e404817e068... \
  --service-account=pfi-backend-runtime@... \
  --execution-environment=gen2 \
  --cpu=1 --memory=512Mi \
  --min-instances=0 --max-instances=2 \
  --concurrency=80 --timeout=300 \
  --no-cpu-throttling \
  --ingress=all --allow-unauthenticated \
  --startup-probe=... (same as legacy) \
  --set-env-vars=PFI_CORS_ALLOWED_ORIGINS=...,PFI_AUTH_DEMO_ENABLED=true,
                 PFI_PERSISTENCE_MODE=postgres,PFI_STORAGE_PROVIDER=gcs,
                 PFI_STORAGE_GCS_BUCKET=pfi-backend-durable-dev \
  --set-secrets=DATABASE_URL=pfi-dev-database-url:latest,
                PFI_AUTH_JWT_SECRET=pfi-dev-jwt-secret:latest
```

Matches the legacy service's timeout (300s) and ingress (`all`), and its
invoker policy (`allUsers` / public — confirmed via
`--allow-unauthenticated`). Cloud SQL: the same Java Connector
configuration (`DATABASE_URL` secret already shaped as
`jdbc:postgresql:///pfi?cloudSqlInstance=pfi-asplanatti-fabrello-v1:us-central1:pfi-postgres-dev&socketFactory=...`)
— no public/raw-IP JDBC, no new secret values created.

## 5. Health (Step 8)

`GET /api/system/health` → `200`, `x-trace-id` present,
`x-pfi-revision: pfi-backend-v2-dev-00001-zx5`, 0.327s (already warm
from the deploy's own probe).

## 6. Runtime diagnostics (Step 9)

```json
{
  "cloudRun": {"service": "pfi-backend-v2-dev", "revision": "pfi-backend-v2-dev-00001-zx5", "configuration": "pfi-backend-v2-dev"},
  "persistence": {"postgresEnabled": true, "mode": "postgres"},
  "storage": {"gcsConfigured": true, "provider": "gcs"},
  "dataSource": {"poolType": "HikariDataSource", "maximumPoolSize": 4, "minimumIdle": 0,
                 "totalConnections": 1, "activeConnections": 0, "idleConnections": 1, "threadsAwaitingConnection": 0}
}
```

`K_SERVICE = pfi-backend-v2-dev` as expected.

## 7. Database validation (Step 10)

Via Cloud SQL Auth Proxy (not the application, an independent check):
`schema_migrations` = 20 rows; `to_regclass('public.domain_durable_object_assets')`
resolves — the durable-storage table from the P0B work is present. Via
the application itself: authenticated `GET /api/ai/audit` returned real
historical audit rows from the shared `pfi` database — full read-path
confirmed working end-to-end, same database as the legacy service.

## 8. Warm load test (Step 11) — 1000 requests, 15 concurrent workers

No `hey`/`wrk`/`ab`/`parallel` available in this environment; used a
small Python `ThreadPoolExecutor` client instead (mix: health / runtime
diagnostics / authenticated DB read, one third each), against the
**plain single-revision service URL**.

| Status | Count |
|---|---|
| 200 | **1000** |
| unexpected 4xx | 0 |
| 500 | 0 |
| 503 | 0 |
| timeout | 0 |

Latency: min 0.214s, p50 0.236s, p95 0.299s, max 0.460s. Window:
`2026-09-22T22:40:31Z`–`22:40:48Z` (17 seconds wall-clock for 1000
requests).

## 9. Instance / platform behavior during load (Step 12)

Exactly **one** `Starting new instance` event in the load-test window
(the original deploy) — zero churn, zero restarts, zero "no available
instance" errors, zero "malformed response" errors. Zero log entries at
`severity>=WARNING` for the entire window.

## 10. Hikari / Cloud SQL (Step 13)

Post-load `runtime-diagnostics`: `totalConnections=3, active=0, idle=3,
waiting=0`. Cross-checked independently via `pg_stat_activity` through
the Auth Proxy: 4 total connections against `pfi` (3 idle from the
Backend's pool + 1 active from the diagnostic query itself) — exact
match. Bounded, no storm, no leak.

## 11. Scale-to-zero (Step 14) and true cold start (Steps 15-16)

Confirmed via logs that the instance shut down naturally
(`ionShutdownHook` → Hikari `Shutdown initiated/completed`) after ~15
minutes of no traffic, each cycle, before every cold-start test below —
this is a genuine scale-to-zero, not an inferred one.

| Cycle | First status | First latency | Second status | Second latency |
|---|---|---|---|---|
| 1 | 200 | 20.667s | 200 | 0.258s |
| 2 | 200 | 20.543s | 200 | 0.245s |
| 3 | 200 | 20.652s | 200 | 0.250s |

All three first-instance-start events confirmed via logs as
`Reason: AUTOSCALING` (genuine cold start, not a leftover warm
instance, not a min-instance replacement). Every cold-start cycle
completed well within the ≥60s client timeout used, and every
immediate second request came back in ~0.25s, confirming the instance
stays warm afterward. Remarkably consistent across all three cycles
(20.54-20.67s cold, 0.245-0.258s warm) — not a fluke.

Per instruction, stopped after cycle 3 (no cycle 4, no further idle
waits).

## 12. Functional smoke (Step 17)

- `GET /api/system/runtime-diagnostics` → 200, healthy state (unchanged
  from §6).
- Authenticated `GET /api/ai/review/history` → 200, real data
  (`reviews`, `measurementsByRunId`, `auditTrail` all populated/shaped
  correctly).
- Invalid input: non-ZIP payload to `POST /api/ai/studies` → **400**
  (`"El archivo del estudio no parece un ZIP valido"`) — controlled
  validation error, never 500/502.

## 13. GCS state (Step 18)

`GCS_RUNTIME_CONFIG_VALIDATED`: confirmed via runtime-diagnostics
(`storage.gcsConfigured=true, provider=gcs`) and via the pre-existing
Stage 4 finding that `GcsObjectStorage`'s fail-closed constructor would
have thrown at startup had the bucket/ADC config been wrong — Green
started cleanly, so this path is live.

`GCS_APPLICATION_WRITE_E2E_PENDING_AI`: no safe Backend-only path
writes to GCS without a successful AI-module call first (`uploadStudy`'s
durable-persist step runs *after* the AI response, per the P0B design).
No artificial endpoint was invented to force this — correctly deferred
to Stage 6/7 once Backend↔AI is wired.

## 14. Final scaling policy (Step 20)

Revision-level (authoritative): `minScale` unset (0), `maxScale=2`.
Concurrency 80, timeout 300s, gen2, `--no-cpu-throttling`. No change
needed — cold start passed cleanly 3/3, so `min=0` is kept as the
default per the explicit cost-conscious instruction, not overridden to
`min=1`.

## 15. Frontend / AI preparation (Steps 21-22, read-only, no changes)

- **Frontend**: still configured with `BACKEND_URL =
  https://pfi-backend-dev-7cxg46zwea-uc.a.run.app` (legacy) — unchanged.
  Green's `PFI_CORS_ALLOWED_ORIGINS` already includes the Frontend's
  actual origin (`https://pfi-frontend-dev-7cxg46zwea-uc.a.run.app`), so
  a future Frontend switch would not require a Backend CORS change.
- **AI**: no `PFI_AI_SERVICE_URL` / `PFI_AI_AUTH_MODE` /
  `PFI_AI_SERVICE_AUDIENCE` env vars set on Green yet (confirmed absent)
  — matches "no execution yet"; Green would accept these the same way
  the legacy service's code already supports, in a future redeploy.

## 16. Cost observation (Step 23)

- `min=0, max=2` — idle compute cost is $0 (proven by 3/3 successful
  scale-to-zero cycles, no lingering instance).
- 1000-request load test + 3 cold-start cycles: a few seconds of actual
  CPU-second billing total, negligible (well under a cent).
- Cloud SQL: no change — this service reuses the existing
  `pfi-postgres-dev` instance and its already-established ~US$11-13/mo
  fixed cost (unchanged by this stage).
- No new GCP resources created besides the Cloud Run service itself
  (which has no fixed cost at `min=0`).

## 17. Legacy service safety confirmation (Step 19)

Confirmed via `gcloud run services describe --format="value(status.traffic)"`
before and after every action in this stage: `100% pfi-backend-dev-00002-jwd`,
never modified.
