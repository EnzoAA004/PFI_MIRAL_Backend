# Cloud Cost Model

**No resource was created to produce this document.** Figures below come from live web search
against current provider pricing pages/analyses (sourced per line, retrieved this session) — not
from memory, not guessed. GCP list pricing changes over time and varies by committed-use
discounts, sustained-use, and region; **always confirm with the
[GCP Pricing Calculator](https://cloud.google.com/products/calculator) before committing budget**.
Every number here is a planning estimate, not an invoice.

## Two profiles

- **DEV-LOW-COST**: minimum viable footprint to keep the stack reachable for solo/small-team
  development. Assumes low, bursty traffic; scale-to-zero everywhere possible.
- **DEMO**: what a live client/thesis-defense demo needs — an always-warm Backend/Frontend
  (no cold-start during a demo), a small but dedicated Cloud SQL instance, no GPU.

Neither profile includes a GPU or a running Qwen 7B instance — EXT-06 stays
`BLOCKED_BY_LOCAL_HARDWARE`/no-cloud-spend per standing instruction.

## Per-service table

| Service | DEV config | DEMO config | Always-on? | Estimated monthly minimum | Usage-dependent | Can be disabled? | Risk |
|---|---|---|---|---|---|---|---|
| Cloud Run — Backend | 1 vCPU / 512 MiB, min-instances 0, max 1–2 | 1 vCPU / 512 MiB, **min-instances 1** (no cold start) | DEV: no; DEMO: yes | DEV: ~$0 idle (pay-per-request only); DEMO: min-instance always billing CPU+memory | Yes (requests, vCPU/GiB-seconds) | Yes (scale to zero) | Low |
| Cloud Run — Frontend (nginx) | 1 vCPU / 256 MiB, min-instances 0 | min-instances 1 for DEMO | DEV: no; DEMO: yes | Similar order to Backend, smaller memory footprint | Yes | Yes | Low |
| Cloud Run — AI service (CPU workloads only) | 1–2 vCPU / 1–2 GiB, min-instances 0 | min-instances 0 or 1 depending on demo script's tolerance for one cold start | Configurable | Same billing shape as above, larger memory | Yes | Yes | Low–Medium (image is the largest of the three, see architecture audit) |
| Cloud Run — `pfi-ai-module` (**already exists**) | Currently: 4 vCPU / 2 GiB, `maxScale=1`, no `minScale` (defaults to 0) | — | No (scale-to-zero observed) | Near-$0 while idle; **verify what it serves and whether it's still needed** | Yes | Already deployed — a deletion decision, not a creation one | **Unknown until content is verified** |
| Artifact Registry (`pfi-containers`, **already exists**, 413.6 MB today) | Same repo, more image versions over time | Same | N/A (storage, not compute) | ~$0.10/GB-month after a 0.5 GB free allowance → at ~400 MB today, effectively free; grows as new SHA-tagged images accumulate | Storage only | No (need it for CI/CD) | Low, but **retention policy needed** (old SHA tags accumulate forever otherwise) |
| Cloud SQL PostgreSQL | `db-f1-micro` (shared-core, 0.2 vCPU, 0.6 GiB) | `db-custom-1-3840` or similar small dedicated tier for demo stability | **Yes — Cloud SQL has no scale-to-zero** | DEV: ~$8/month instance + storage; DEMO: notably more (dedicated core pricing, not shared-core) | Storage (~$0.22/GB-month SSD) + backups (~$0.11/GB-month) add on top | **No** — this is the one service that is always-on by design | **Highest fixed-cost item in the whole stack** |
| Cloud Storage (model artifacts / outputs, reusing the existing `pfi-rm-lumbar-artifacts-2026-ef` bucket or a new one) | Standard class, regional | Same | N/A | ~$0.020/GB-month (US regional) | Egress if served publicly | Yes | Low |
| Secret Manager | A handful of secrets (JWT, DB password) | Same | N/A | First 6 versions + first 10k accesses/month free; beyond that $0.06/version/month + $0.03/10k accesses | Negligible at this scale | Yes but shouldn't be | Low |
| Cloud Logging / Monitoring | Default free allotment | Default free allotment | N/A | Free tier covers small-scale logging/metrics; costs appear only at high volume | Yes at scale | Partially (retention/exclusion filters) | Low at this project's scale |
| Network egress (internet, Premium Tier default) | Minimal (mostly internal Cloud Run↔Cloud SQL traffic, which is not internet egress) | Same, plus demo viewers' traffic | N/A | $0.12/GB for the first 1 TB after a small always-free allowance | Yes | N/A | Low unless the AI service starts serving large images/heatmaps at volume |

## Rough monthly floor (DEV-LOW-COST)

Cloud SQL `db-f1-micro` (~$8) + minimal storage/backup (a few dollars) + everything else at or near
$0 (scale-to-zero Cloud Run, free-tier Artifact Registry/Secret Manager/Logging at this volume) ≈
**low double-digit USD/month**, dominated almost entirely by Cloud SQL. This matches the common
industry observation that Cloud SQL, not Cloud Run, is the line item that never goes to zero.

## Rough monthly floor (DEMO)

Add: Cloud Run min-instances on Backend+Frontend (small but non-zero, since they're now billed
for idle allocated CPU/memory too), a larger Cloud SQL tier for reliability during a live
demo, and possibly the AI service kept warm. Order-of-magnitude higher than DEV, still well within
a "coffee money" range for a short demo window if instances are torn down (or scaled back to 0)
right after — the real requirement is to **not leave DEMO's always-on settings running
indefinitely**.

## Special attention items (as requested)

- **Cloud SQL**: the only service in this stack with no scale-to-zero — the single line item most
  likely to surprise someone if left running. CLOUD-05 must document explicit start/stop or
  tear-down procedure.
- **GPU**: not included in either profile. A real Qwen2.5-7B-Instruct deployment would need one
  (EXT-06 already documents this is blocked without explicit cost authorization).
- **Artifact scanning**: Artifact Registry's optional vulnerability scanning is a separate billed
  feature (not itemized above — not yet a requirement for this stage; revisit before CLOUD-03).
- **Egress**: low risk today given the product's data shapes (JSON, small PNG heatmaps), but worth
  re-checking if the AI service starts returning larger images/volumes.

## Sources

- [Cloud Run pricing](https://cloud.google.com/run/pricing)
- [Google Cloud SQL Pricing 2026 — instance costs](https://www.usage.ai/blogs/gcp/cloud-sql/pricing/)
- [Google Cloud SQL Pricing 2026 — storage/backup](https://securityboulevard.com/2026/05/google-cloud-sql-pricing-2026-instance-costs-storage-and-what-the-calculator-hides/)
- [Artifact Registry pricing](https://cloud.google.com/artifact-registry/pricing)
- [Google Cloud Storage Pricing 2026](https://www.nops.io/blog/google-cloud-storage-pricing/)
- [Secret Manager pricing](https://cloud.google.com/secret-manager/pricing)
- [Google Cloud Egress Pricing 2026](https://lp.zeroservices.eu/articles/google-cloud-egress-pricing-2026/)

**None of these figures were used to create, resize, or commit to any resource this session.**
