# CLOUD-05 — Cost Hard Gate

**This stage introduces the first fixed, always-on cost in this project's cloud footprint.** Cloud
Run (Backend, Frontend) scales to zero and costs effectively nothing at rest; Cloud SQL does not —
it runs continuously unless manually stopped, and even stopped it retains its storage cost. This
document is the required hard-gate check before any `terraform apply` of `enable_cloud_sql = true`.

## Sourced pricing basis

All rates below are from the live Cloud Billing Catalog API
(`cloudbilling.googleapis.com/v1/services/9662-B51E-5089/skus`, `us-central1`/Iowa, ~15,000 SKUs
paged and inspected, captured 2026-09-04) — not historical documentation or a remembered "~$8/month"
estimate. Full SKU table and reasoning in `CLOUD_05_CLOUD_SQL_DESIGN.md`.

## Proposed configuration and cost

**`db-custom-1-3840` (1 vCPU / 3.75 GiB), 10 GB PD_SSD, ZONAL, backups off:**

| Period | Cost |
|---|---|
| 24 hours | ~$1.68 |
| 7 days | ~$11.75 |
| 30 days (730h) | **~$51.01/month** |

Breakdown: compute `(1 × $0.0413/vCPU-hr + 3.75 × $0.0070/GiB-hr) × 730h = $49.31` + storage
`10 GB × $0.17/GB-mo = $1.70`.

## Stoppable, and what stopping actually saves

Cloud SQL instances can be stopped (`gcloud sql instances patch --activation-policy=NEVER`), which
halts **compute** billing but **not storage** billing (the 10 GB disk persists and keeps billing
regardless of instance run state). Stopped-instance cost: **~$1.70/month** (storage only). This is a
real, usable cost lever for a thesis project not run 24/7 — e.g. stopping the instance between work
sessions.

## Comparison to the CLOUD-01-era estimate

The original `CLOUD_COST_MODEL.md`/CLOUD-01 default (`db-f1-micro`, "~$8/month") is **no longer a
valid configuration for a new instance** — confirmed via the live catalog: `db-f1-micro` only exists
today as an "Extended support" SKU tied to already-EOL PostgreSQL major versions (9.6–13), which this
design does not use (`POSTGRES_15` is current, standard-support). The real cost of a valid, current
configuration (~$51/month) is materially higher than the stale ~$8/month figure — this is the primary
finding this hard gate exists to surface before any apply.

## Additive cost, no other stage's spend changes

No change to Cloud Run pricing (still scale-to-zero, same images, same instance counts), no change
to Artifact Registry, WIF, or Cloud Storage costs — this stage's entire cost delta is the Cloud SQL
instance above (~$51/month) plus the two Secret Manager secret containers (Secret Manager's free
tier covers up to 6 active secret versions per month; 2 containers with 1 version each stays free).

## Sizing discipline

No larger tier, no Regional/HA, no PITR is proposed — the DEV-min row in
`CLOUD_05_CLOUD_SQL_DESIGN.md`'s comparison table is the only configuration this stage requests
authorization for. The DEMO and future-prod rows in that table are shown only for comparison, not
requested here.

## Gate status

**Not yet authorized.** This document, together with `CLOUD_05_CHANGE_PLAN.md`'s Plan 2 diff, is the
artifact the user reviews to approve or reject the ~$51/month recurring cost before any
`terraform apply -var="enable_cloud_sql=true"` is run.
