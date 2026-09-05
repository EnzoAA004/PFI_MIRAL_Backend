# CLOUD-06 — Cost Check

**New cost this stage: $0.** CLOUD-06 creates no GCP resource — the figures below are a hypothetical
cost model for a future DEMO deploy (Cloud SQL/Secrets/Monitoring/AI all still off), not a bill for
anything that exists today.

## Sourced pricing (Cloud Billing Catalog API, Cloud Run service `152E-C115-5142`, `us-central1`,
full 703-SKU catalog, captured 2026-09-05)

| Billing mode | Resource | Rate |
|---|---|---|
| Request-based (CPU throttled while idle, `cpu_idle=true`) | Active CPU | `4856-B847-F1EB` $0.0000240/vCPU-second |
| Request-based | Memory | `02A2-9231-36A6` $0.0000025/GiB-second |
| Request-based, min-instance kept warm | Idle CPU (while not serving) | `7EBB-8579-2C98` $0.0000025/vCPU-second — 10× cheaper than active CPU |
| Request-based, min-instance kept warm | Idle memory | `740D-08F2-7A11` $0.0000025/GiB-second (same as active) |
| Instance-based (CPU always allocated, `cpu_idle=false`) | CPU, full lifetime | `011E-3072-CBDB` $0.0000180/vCPU-second |
| Instance-based | Memory, full lifetime | `F6D2-B591-E54F` $0.0000020/GiB-second |
| — | Requests | $0.40/million requests (Cloud Run public pricing, already cited in `CLOUD_04_COST_CHECK.md`) |

This project's Backend (`cpu_idle` unset → defaults `false`, i.e. **CPU always allocated**) and
Frontend (`cpu_idle=true`, forced by the <512Mi memory-floor rule found in CLOUD-04) use different
billing modes, so their `min_instances=1` costs are computed differently below.

## Scenario A: `min_instances=0` (both services) — DEMO's own default

Idle cost: **$0** — both services scale to zero when no request is in flight, exactly like DEV
(confirmed in `CLOUD_04_COST_CHECK.md`). Cost only accrues while actively handling a request, and at
expected demo-session traffic volumes (tens to low hundreds of requests, well under Cloud Run's
always-free monthly allotment of 2M requests / 180,000 vCPU-seconds / 360,000 GiB-seconds), the
marginal cost rounds to **$0**, same reasoning CLOUD-04 already established for DEV.

| Period | Cost |
|---|---|
| 1 hour idle | $0 |
| 24 hours idle | $0 |
| 7 days idle | $0 |
| 30 days idle | $0 |

The tradeoff: a cold start (container init, JVM startup for Backend) on the first request after any
idle period — acceptable for an on-demand demo, not for a live audience watching the first click.

## Scenario B: `min_instances=1` (both services) — no cold start, for a scheduled live demo

**Backend** (`cpu_idle=false`, 1 vCPU / 512 MiB, always allocated, instance-based billing, 24/7):

- CPU: `1 × $0.0000180/s = $0.0000180/s`
- Memory: `0.5 GiB × $0.0000020/GiBy.s = $0.0000010/s`
- Total: `$0.0000190/s`

**Frontend** (`cpu_idle=true`, 1 vCPU / 256 MiB, kept warm but CPU throttled while idle):

- Idle CPU: `1 × $0.0000025/s = $0.0000025/s`
- Memory (always billed, idle or not): `0.25 GiB × $0.0000025/GiBy.s = $0.000000625/s`
- Total (idle floor, excluding actual request-serving spikes): `$0.000003125/s`

| Period | Backend | Frontend (idle floor) | Combined |
|---|---|---|---|
| 1 hour | $0.0684 | $0.01125 | **~$0.08** |
| 24 hours | $1.64 | $0.27 | **~$1.91** |
| 7 days | $11.49 | $1.89 | **~$13.38** |
| 30 days (2,592,000s) | $49.25 | $8.10 | **~$57.35/month** |

Backend dominates this scenario's cost — its `cpu_idle=false` choice is deliberate (not a fallback of
convenience): a warmed-but-CPU-throttled JVM would still show a brief CPU-catch-up delay on the first
request after any idle gap, defeating the point of `min_instances=1` for a live demo. Frontend has no
such concern (a static nginx server has near-zero CPU need even when "cold"), so keeping it on the
cheaper throttled-idle rate is the right tradeoff there.

**Recommendation**: keep `backend_min_instances`/`frontend_min_instances` at their `0` default
year-round; set them to `1` only for the duration of a scheduled demo session (a few hours at most),
via a `terraform apply -var="backend_min_instances=1" -var="frontend_min_instances=1"` immediately
before the session and reverted immediately after — at ~$0.08/hour combined, an all-day demo session
costs under $2, and reverting to `0` the same day keeps monthly cost near $0 rather than $57.

## What this stage explicitly excludes from cost

No Cloud SQL (`enable_cloud_sql=false` — DEMO's own separate ~$9.37/month `db-f1-micro` cost, if
ever authorized, is scoped entirely to CLOUD-05's cost gate, not this stage). No Secret Manager
resource cost (2 secret containers stay within the free tier regardless). No Monitoring alert
policies. No dedicated AI Cloud Run service. No new Artifact Registry storage (no rebuild — DEMO
would reuse the exact image already stored and billed for by DEV's publish).

## Gate status

No cost is being requested or authorized by this document — it exists so that if/when DEMO is
authorized for a real apply, the min_instances tradeoff is already quantified rather than decided
blind.
