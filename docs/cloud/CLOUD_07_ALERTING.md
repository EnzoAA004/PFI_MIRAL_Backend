# CLOUD-07 — Alerting Design

**Status: DESIGN + TERRAFORM MODELED, NOT CREATED.** `modules/monitoring` now declares the resources
below, all gated behind `enable_alerts`/`enable_uptime_checks`/`enable_notification_channels`
(all `false` by default in every environment). Confirmed via the Monitoring REST API: zero alert
policies, zero uptime checks, zero notification channels exist in the project today.

## Alert policies

### A. Backend 5xx rate elevated

| | |
|---|---|
| Signal | `run.googleapis.com/request_count`, filtered `response_code_class="5xx"`, `service_name="pfi-backend-*"` |
| Threshold | > 5 (rate, aligned) |
| Window | 300s (5 min) |
| Severity | High — direct user-facing failure |
| Action | Investigate Backend logs; consider rollback per `CLOUD_07_ROLLBACK.md` policy |
| False-positive risk | Low at expected low DEV/DEMO traffic — a genuine burst of 5+ errors in 5 minutes is unlikely from normal use; more likely from an actual bug or a bad deploy |
| Terraform | `google_monitoring_alert_policy.backend_5xx`, gated `enable_alerts` |

### B. Frontend 5xx rate elevated

Same shape as A, targeting `pfi-frontend-*`. Nginx serving static assets has a much lower expected
5xx baseline than a JVM backend; the same threshold/window is used for now rather than inventing an
unvalidated separate number — revisit once real traffic data exists.

| | |
|---|---|
| Terraform | `google_monitoring_alert_policy.frontend_5xx`, gated `enable_alerts` |

### C. Cloud Run revision crash/start failure

**Not modeled as a distinct alert policy.** Reasoning: Cloud Run does not expose a clean "crash-loop"
metric — the most reliable signal that a revision cannot function is that it fails its own health
check, which Signal D (uptime check) already catches regardless of *why* (crash, OOM, missing
secret, bad startup config). Modeling a separate, weaker proxy metric (e.g. instance count dropping
to zero unexpectedly) would add false-positive risk (scale-to-zero during genuine idle looks
identical) without adding real detection power over Signal D. Documented as intentionally folded
into D, not skipped.

### D. Backend health unavailable (uptime check)

| | |
|---|---|
| Signal | `google_monitoring_uptime_check_config` against `${backend_url}/api/system/health` (the real public liveness path — CLOUD-04 already found `/actuator/health` doesn't exist) |
| Threshold | Check fails (< 1, i.e. not passing) |
| Window | 300s check period, 300s alert duration |
| Severity | Critical — total or partial outage |
| Action | Immediate rollback evaluation (`cloud-07-rollback.yml`) |
| False-positive risk | Low — a single missed check within the 5-minute alert window would need to be a sustained failure, not a transient network blip, to fire |
| Terraform | `google_monitoring_uptime_check_config.backend_health` + `google_monitoring_alert_policy.backend_uptime_failure`, both gated `enable_uptime_checks`/`enable_alerts` |

### E. Cloud SQL unavailable (future — not modeled, no instance exists)

| | |
|---|---|
| Signal (future) | `cloudsql.googleapis.com/database/up` |
| Threshold (future) | `< 1` |
| Status | **Not created.** No Cloud SQL instance exists (`enable_cloud_sql=false` — CLOUD-05 is plan-only). Adding this alert policy today would either fail to apply (referencing a non-existent instance) or need to be unconditionally valid Terraform with no target — neither is useful. Documented for CLOUD-05's eventual activation, implemented then, not now. |

### F. DB connection saturation (future — not modeled)

| | |
|---|---|
| Signal (future) | `cloudsql.googleapis.com/database/postgresql/num_backends` vs. the instance's `max_connections` |
| Status | Same as E — deferred until Cloud SQL exists. `db-f1-micro`'s connection limit is notably lower than a dedicated tier's (a real operational detail worth alerting on once the instance is real), but there is nothing to measure yet. |

### G. Storage nearing limit (future — not modeled)

| | |
|---|---|
| Signal (future) | `cloudsql.googleapis.com/database/disk/utilization` |
| Status | Same as E/F — `disk_autoresize=false` (CLOUD-05's deliberate choice) makes this alert specifically valuable once Cloud SQL exists (an out-of-space error should be *caught by this alert*, not discovered as a Backend outage) — not before. |

## Notification channels

`google_monitoring_notification_channel` (type `email`) is modeled, gated by
`enable_notification_channels` **and** a non-empty `notification_channel_email` variable — both stay
at their default (`false`/`""`) in every environment today, so **no channel is created even with
`enable_monitoring=true`** in the CLOUD-07 preview plan (confirmed: the preview plan shows 4 alerting
resources, not 5 — no notification channel). Alert policies would exist and be visible in Cloud
Monitoring's console but would notify no one until a real address is supplied.

## Cost-aware alerting (see `CLOUD_07_COST_CHECK.md` for the full estimate)

Every alert policy above evaluates already-collected built-in Cloud Run/Cloud SQL metrics — alert
policy evaluation itself has no separate line-item cost in Cloud Monitoring's pricing (billing is for
metric *ingestion* and *API calls*, not for the policies that read already-ingested data). The uptime
check is the one genuinely new, billed resource this design introduces (a small number of synthetic
HTTP requests per month) — quantified in `CLOUD_07_COST_CHECK.md`.
