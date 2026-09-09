# CLOUD-05 — Cost Hard Gate

**Superseded by the CLOUD-05 re-audit — see `CLOUD_05_COST_OPTIMIZATION_REVIEW.md` for the full
investigation.** The original version of this document proposed `db-custom-1-3840` at ~$51/month
because it (incorrectly) concluded `db-f1-micro` was unavailable for new instances. That conclusion
was wrong (incomplete SKU search + unexhausted API pagination) — `db-f1-micro` is confirmed available
and is now this stage's proposed configuration, at roughly one-fifth the cost. This document is
updated in place to reflect the corrected figures; nothing below has been authorized or applied.

**This stage introduces the first fixed, always-on cost in this project's cloud footprint.** Cloud
Run (Backend, Frontend) scales to zero and costs effectively nothing at rest; Cloud SQL does not —
it runs continuously unless manually stopped, and even stopped it retains its storage cost. This
document is the required hard-gate check before any `terraform apply` of `enable_cloud_sql = true`.

## Sourced pricing basis

All rates below are from the live Cloud Billing Catalog API
(`cloudbilling.googleapis.com/v1/services/9662-B51E-5089/skus`, `us-central1`/Iowa, the full ~40,500
SKUs for the Cloud SQL service, pagination exhausted — captured 2026-09-04) plus the live Cloud SQL
Admin API `tiers.list` endpoint. Full SKU table and reasoning in `CLOUD_05_CLOUD_SQL_DESIGN.md` and
`CLOUD_05_COST_OPTIMIZATION_REVIEW.md`.

## Proposed configuration and cost

**`db-f1-micro` (shared-core, `edition=ENTERPRISE`, ~614 MiB RAM), 10 GB PD_SSD, ZONAL, backups off:**

| Period | Cost |
|---|---|
| 1 hour | ~$0.0105 |
| 24 hours | ~$0.31 |
| 7 days | ~$2.16 |
| 30 days (730h) | **~$9.37/month** |

Breakdown: compute `$0.0105/instance-hr × 730h = $7.665` + storage `10 GB × $0.17/GB-mo = $1.70`.

## Stoppable, and what stopping actually saves

Cloud SQL instances can be stopped (`gcloud sql instances patch --activation-policy=NEVER`), which
halts **compute** billing but **not storage** billing (the 10 GB disk persists and keeps billing
regardless of instance run state) — and if a public IP is retained on a stopped instance, GCP may
still bill a small reserved-IP charge (see `CLOUD_05_COST_OPTIMIZATION_REVIEW.md` item 12 for the
exact caveat). Stopped-instance cost: **~$1.70/month** (storage only, plus the possible reserved-IP
charge). This is a real, usable cost lever for a thesis project not run 24/7 — e.g. an on-demand
start-before-demo, stop-after-demo cadence.

## Comparison to prior estimates

| Estimate | Tier | Monthly cost | Status |
|---|---|---|---|
| CLOUD-01 original | `db-f1-micro` | "~$8/month" (undocumented historical approximation) | Directionally correct, not independently sourced at the time |
| CLOUD-05 first pass | `db-custom-1-3840` | ~$51.01/month | **Wrong premise** — incorrectly concluded `db-f1-micro` was unavailable |
| **CLOUD-05 corrected** | `db-f1-micro` | **~$9.37/month** | Sourced from live tiers.list + exhaustively-paged Billing Catalog SKUs |

The corrected figure (~$9.37/month) is close to, and validates, the original CLOUD-01 approximation
— the CLOUD-05 first-pass conclusion was the outlier, caused by an incomplete audit, not the CLOUD-01
estimate being wrong.

## Additive cost, no other stage's spend changes

No change to Cloud Run pricing (still scale-to-zero, same images, same instance counts), no change
to Artifact Registry, WIF, or Cloud Storage costs — this stage's entire cost delta is the Cloud SQL
instance above (~$9.37/month) plus the two Secret Manager secret containers (Secret Manager's free
tier covers up to 6 active secret versions per month; 2 containers with 1 version each stays free).

## Sizing discipline

No larger tier, no Regional/HA, no PITR is proposed — the DEV-min row (`db-f1-micro`) in
`CLOUD_05_CLOUD_SQL_DESIGN.md`'s comparison table is the only configuration this stage requests
authorization for. The dedicated (`db-custom-1-3840`) and future-prod rows in that table remain
documented fallbacks only, not requested here.

## Gate status

**Not yet authorized.** This document, together with `CLOUD_05_COST_OPTIMIZATION_REVIEW.md` and
`CLOUD_05_TERRAFORM_PLAN_OUTPUT_F1MICRO.txt`, is the artifact the user reviews to approve or reject
the ~$9.37/month recurring cost before any `terraform apply -var="enable_cloud_sql=true"` is run.
