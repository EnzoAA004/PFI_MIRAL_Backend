# CLOUD-07 — Cost Check

**New cost this stage: $0 — nothing was created.** The figures below quantify what a future,
separately authorized `enable_monitoring=true` apply would cost; CLOUD-07 itself creates no GCP
resource.

## Sourced pricing (Cloud Billing Catalog API, `Cloud Logging` service `5490-F7B7-8DF6`, `Cloud
Monitoring` service `58CD-E7C3-72CA`, global SKUs — logging/monitoring pricing is not region-split
the way Cloud Run/SQL are — captured 2026-09-05)

| Resource | SKU | Rate |
|---|---|---|
| Log ingestion/storage | `143F-A1B0-E0BE` "Log Storage cost" | $0.50/GiB (first 50 GiB/month free, per Cloud Logging's published free tier) |
| Log retention beyond default | `F4AE-5A52-ACE3` "Log Retention cost" | $0.01/GiB-month (default 30-day retention is free; this only applies to extended retention, not configured anywhere in this design) |
| Uptime check executions | `CA14-D3DE-E67F` "Monitoring Uptime Checks" | $0.0003/execution |
| Built-in GCP metrics (Cloud Run, Cloud SQL) | — | **Free** — Google's own built-in service metrics have no ingestion/query cost; only custom metrics, Prometheus-sourced metrics, and API requests beyond a free allotment are billed (`A924-09D0-8854`, `A4E4-DF03-CDB6`, `55A9-89F6-775B`) — none of which this design uses |
| Notification channels, alert policy evaluation | — | **Free** — no separate SKU exists for either; only the underlying metric/log data being evaluated can cost anything, and that data is already free here |

## What already costs something today, unrelated to CLOUD-07

Cloud Run has streamed logs to Cloud Logging automatically since CLOUD-04 — this is not a new cost
CLOUD-07 introduces, it is the platform's default behavior. At this project's DEV/DEMO smoke-test
traffic volume (tens to low hundreds of requests), monthly log volume is on the order of low
megabytes — several orders of magnitude under the 50 GiB/month free tier. **Rounds to $0 today, and
would keep rounding to $0 under CLOUD-07's design**, since nothing here increases log volume.

## What CLOUD-07's design would add, once authorized

**The uptime check is the one line item with a real, non-zero cost**, because it is the one new
resource this design proposes:

- Configured period: 300s (5 minutes) → 12 checks/hour → 288/day → ~8,760/month (30-day month).
- At $0.0003/execution: `8,760 × $0.0003 = $2.63/month` for a single checking location.
- **Caveat, not yet resolved in the Terraform**: Cloud Monitoring uptime checks run from multiple
  global checking locations by default unless explicitly restricted via `selected_regions` (not set
  in the current `google_monitoring_uptime_check_config.backend_health` resource) — the real
  execution count, and therefore real cost, could be a multiple of the single-location estimate
  above (commonly 3-6 regions). **Before ever authorizing `enable_uptime_checks=true` for real**, add
  an explicit `selected_regions` constraint to the module and recompute this line item precisely —
  flagged here as an open item, not silently assumed away.

| Period | Uptime check cost (single-location estimate) |
|---|---|
| 1 hour | $0.0036 |
| 24 hours | $0.0864 |
| 7 days | $0.605 |
| 30 days | **~$2.63/month** (before the multi-region caveat above is resolved) |

Alert policies themselves (Backend 5xx, Frontend 5xx, uptime-check-failure) add **$0** — they
evaluate already-free built-in metrics and the uptime check's own (small) cost is counted above, not
duplicated. A notification channel (email) adds **$0** — no channel SKU exists.

## DEV vs DEMO vs future production-like

| Environment | Monitoring cost if enabled | Reasoning |
|---|---|---|
| DEV (low-traffic thesis) | ~$2.63/month (uptime check only, pending the multi-region caveat) | Same design, same signals — a thesis DEV environment doesn't need less monitoring, it needs monitoring that costs almost nothing, which this already is |
| DEMO | Same, ~$2.63/month | No different resource set proposed for DEMO — `modules/monitoring`'s wiring is identical between environments (see `CLOUD_06_DEV_TO_DEMO_PROMOTION.md`'s "DEV and DEMO stay structurally identical" principle) |
| Future production-like | Would add: Cloud SQL alert policies (E/F/G, `CLOUD_07_ALERTING.md`) once Cloud SQL is real — still built-in-metric-based, still ~$0 marginal; a real notification channel (still $0); possibly a second uptime check for Frontend (~$2.63/month more); log retention beyond 30 days if a compliance need ever requires it (the only line item that could become non-trivial, and only if deliberately configured) | Nothing here assumes production yet — CLOUD-07 stays DEV/DEMO scoped |

## Gate status

No cost is being requested or authorized by this document. If `enable_monitoring=true` is ever
authorized for a real apply, resolve the uptime-check multi-region caveat first so the real cost is
known precisely rather than estimated from a single-location assumption.
