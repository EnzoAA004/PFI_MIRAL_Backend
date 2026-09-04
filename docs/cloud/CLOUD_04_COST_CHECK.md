# CLOUD-04 — Cost Check

**No resource has been created. Figures below are estimates from public Cloud Run pricing plus the
existing `CLOUD_COST_MODEL.md` DEV profile — not a bill, not a guarantee.**

## What CLOUD-04 would add

| Resource | Config | Idle cost | Usage-based cost |
|---|---|---|---|
| `pfi-backend-dev` | 1 vCPU / 512 MiB, `min_instance_count=0`, `max=2`, concurrency 80 | **$0** — scale-to-zero, no allocated instance when idle | vCPU/GiB-seconds only while handling a request + $0.40/million requests, both after Cloud Run's always-free monthly allotment (180,000 vCPU-s, 360,000 GiB-s, 2M requests) |
| `pfi-frontend-dev` | 1 vCPU / 256 MiB, `min_instance_count=0`, `max=2` | **$0** — same scale-to-zero | Same shape, smaller memory footprint |
| `pfi-frontend-runtime-dev` (service account) | — | $0 — IAM identities are free | — |
| Public egress | Only for actual smoke-test/browser traffic this stage | — | $0.12/GB after a small always-free allowance (Premium Tier) — negligible at smoke-test volume |

This matches `CLOUD_COST_MODEL.md`'s existing DEV-profile line items for Backend/Frontend Cloud Run
(*"DEV: ~$0 idle (pay-per-request only)"*), which were estimated before any resource existed;
CLOUD-04 changes nothing about that shape, only makes the exact image/config concrete.

## What CLOUD-04 explicitly does NOT add

- No Cloud SQL (`enable_cloud_sql=false`) — the cost model's own **"highest fixed-cost item in the
  whole stack"** stays at $0 this stage.
- No new AI Cloud Run service (`enable_ai_service=false`).
- No Secret Manager, no Monitoring alert policies, no additional Artifact Registry storage (no
  image rebuild — the two SHA-tagged images already exist from CLOUD-03's publish and were already
  counted in that stage's cost check).

## Estimate for the smoke-test workload specifically

Expected request volume for CLOUD-04's own verification (health checks, a handful of smoke-test
HTTP calls, one browser load) is on the order of tens to low hundreds of requests, each completing
in well under a second. This is several orders of magnitude below Cloud Run's always-free monthly
allotment (2 million requests, 180,000 vCPU-seconds, 360,000 GiB-seconds) by itself — the smoke
test's own marginal cost rounds to **$0**.

**Not guaranteed as literally $0** if this DEV deployment is left running and receives meaningful
organic traffic later (e.g. left public and discovered, or used for extended manual testing beyond
the smoke suite) — scale-to-zero means idle cost stays $0, but sustained request volume beyond the
free tier would start accruing the small per-request/compute-time charges above. No alerting or
budget cap is created this stage (Monitoring is CLOUD-07).

## Sources

Same as `CLOUD_COST_MODEL.md`: [Cloud Run pricing](https://cloud.google.com/run/pricing),
[Google Cloud Egress Pricing 2026](https://lp.zeroservices.eu/articles/google-cloud-egress-pricing-2026/).
