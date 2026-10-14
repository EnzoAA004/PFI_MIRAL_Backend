# Cloud Stage 10 — Real Cost & Performance Measurement (2026-09-23)

Goal: obtain defensible real Cloud runtime/cost evidence for the PFI
financial model, calibrating the prior estimated USD/study values
against observed Cloud behavior. Measurement-focused; no architecture
change.

**Result: real cost/performance model built from live measurements.
One significant, unrelated cost anomaly was found and flagged (not
fixed, per instruction) — see section 12.**

## 1. Billing / credit snapshot (Step 1)

- Billing account `012206-071130-92E8E1`: linked, **enabled**, open.
- **No BigQuery billing export configured** (`bq ls` returns no
  datasets) -- actual USD line-item costs and remaining trial credit
  balance are **not queryable via CLI/API** in this project; the
  Console UI is the only source for that, which this stage cannot
  read. Stated explicitly rather than guessed.
- This report's cost figures are therefore **derived from real
  observed resource usage applied to Google Cloud's current published
  per-unit prices** (Cloud Run compute, Cloud SQL, GCS, Artifact
  Registry, Secret Manager -- each individually sourced, see section
  19), not from an actual billing statement. This is stated
  explicitly everywhere a dollar figure appears below.

## 2. Resource config snapshot (Step 2) -- verified live, not assumed

| Service | CPU | RAM | min/max | Concurrency | Timeout |
|---|---|---|---|---|---|
| Backend Green (`pfi-backend-v2-dev`) | 1 vCPU | 512 Mi | 0/2 | 80 | 300s |
| AI (`pfi-ai-module`) | 4 vCPU | 2 Gi | 0/1 | 1 | 300s |
| Frontend (`pfi-frontend-dev`) | 1 vCPU | 256 Mi | 0/2 | 80 | -- |

Cloud SQL (`pfi-postgres-dev`): tier `db-f1-micro`, 10 GB `PD_SSD`,
region `us-central1`, availability `ZONAL` (no HA), backups
**disabled**, PITR **disabled**.

GCS (`pfi-backend-durable-dev`): storage class `STANDARD`, region
`US-CENTRAL1`, uniform bucket-level access, public access prevention
**enforced**.

## 3. Fixed-cost classification (Step 3) -- verified

| Resource | Classification |
|---|---|
| Cloud SQL | **FIXED_MONTHLY** (zonal instance never scales to zero) |
| Cloud Run Backend | **NEAR_ZERO_IDLE** (`min=0`, confirmed scales to zero when idle) |
| Cloud Run AI | **NEAR_ZERO_IDLE** (`min=0`, confirmed) |
| Cloud Run Frontend | **NEAR_ZERO_IDLE** (`min=0`, confirmed) |
| GCS storage | **VARIABLE_USAGE** (bytes stored) |
| Secret Manager | **NEAR_ZERO** (2 active versions, free tier covers 6) |
| Artifact Registry | **VARIABLE_USAGE**, small (1.35 GB stored, mostly free-tier) |

## 4. Test fixture (Step 4)

Identical to the Stage 7 canonical synthetic fixture, reused across
every batch to hold this variable constant: pydicom-generated, 5
series, 30 slices, 44,326 bytes, random-noise pixel data, no PHI.
Workflow per study: `POST /api/ai/studies` (upload/ingest) ->
`POST /api/ai/pipeline/run` with `inferenceMode=real_baseline` on the
sagittal series (real PyTorch inference, not the demo/contract
fallback).

## 5. Measurement windows (Step 5, UTC)

| Window | Start | End |
|---|---|---|
| Baseline (pre-warmup) | 2026-09-23T15:01:48Z | -- |
| Warmup (2 studies, uncounted) | ~15:02:xx | ~15:03:xx |
| Batch 10 | 2026-09-23T15:03:15Z | 2026-09-23T15:07:26Z |
| Batch 25 | ~15:07:xx | 2026-09-23T15:19:10Z |

## 6. Warmup (Step 6)

2 uncounted studies run first to separate cold-start residue from
steady state: 27,742 ms then 19,664 ms total -- the first still
carried some warm-up cost from the prior idle gap since Stage 9; the
second was already at steady state. **Zero cold starts occurred during
either the warmup or the two measured batches** (confirmed via
`gcloud logging read ... "Starting new instance"`, zero matches in
both batches' windows) -- Backend and AI stayed on the same single
warm instance throughout this entire stage.

## 7-8. 10-study batch (Steps 7-8)

**10/10 success**, real Cloud E2E (upload -> Backend -> real AI
inference -> durable outputs -> response) each time.

| Metric | mean | p50 | p95 | min | max |
|---|---|---|---|---|---|
| upload_ms | 690 | 689 | 736 | 637 | 736 |
| inference_ms | 18,708 | 18,734 | 20,117 | 17,409 | 20,117 |
| total_ms | 19,407 | 19,465 | 20,814 | 18,051 | 20,814 |

No cold/warm split needed (all warm). Backend/AI instance starts: 0.
Full per-study rows in `CLOUD_STAGE_10_MEASUREMENTS.csv`.

## 9. Cost evidence after 10 (Step 9)

Postgres: `pfi` DB size 9,319 kB -> 9,327 kB (+8 kB for 12 studies incl.
warmup); `pg_stat_activity` 2 connections; `domain_durable_object_assets`
1 -> 13 rows (**exactly +12, one row per study, zero duplication**).
GCS: 1,374,116 -> 6,071,996 bytes (**+4,697,880 bytes / 12 studies =
391,490 bytes/study**).

**BILLING_COST_PENDING_PROPAGATION** -- no billing export exists to
confirm actual USD charged for this window; usage (above) is the real,
directly-observed evidence; the dollar model in sections 14-19 applies
published rates to it.

## 10-11. 25-study batch and stability analysis (Steps 10-11)

**25/25 success.**

| Metric | mean | p50 | p95 | min | max |
|---|---|---|---|---|---|
| upload_ms | 676 | 671 | 757 | 507 | 893 |
| inference_ms | 18,424 | 18,090 | 21,132 | 16,256 | 21,739 |
| total_ms | 19,109 | 18,794 | 21,812 | 16,910 | 22,420 |

GCS after batch 25: 15,859,246 bytes. Delta from post-batch-10:
**+9,787,250 bytes / 25 studies = 391,490 bytes/study -- identical to
the nines to the batch-10 figure.** Postgres: DB size 9,399 kB (+72 kB
for 25 studies); `domain_durable_object_assets` 13 -> 38 rows
(**exactly +25, again zero duplication**). Zero new Backend/AI
instance starts throughout.

**10 vs 25 comparison**: latency distributions overlap almost
completely (p50 19,465 vs 18,794 ms; p95 20,814 vs 21,812 ms -- both
well within the other's min-max range); GCS bytes/study identical to
6 significant figures across both batches; durable-asset row growth
exactly 1:1 in both. **Metrics had already converged by 10 studies and
confirmed by 25** -- no material additional information from further
scale.

## 12. 50-study gate (Step 12) -- stopped at 25, per confirmed decision

Presented the above convergence evidence and asked before running a
50-study batch, since the incremental statistical value was clearly
minimal (near-identical results twice in a row) even though the
incremental cost would have been trivial (~$0.06 of real compute).
**Confirmed: do not run the 50-study batch.** 25 measured studies
(plus 2 warmup, 35 successful runs total, 0 failures) is the evidence
base for this report.

## 13. Combined statistics (n=35)

| Metric | mean | p50 | p95 | min | max |
|---|---|---|---|---|---|
| upload_ms | 680 | 679 | 757 | 507 | 893 |
| inference_ms | 18,505 | 18,368 | 21,132 | 16,256 | 21,739 |
| total_ms | 19,194 | 19,228 | 21,812 | 16,910 | 22,420 |

## 14. Cloud Run cost model (Step 14) -- derived from observed usage

Using combined-batch **means** (19.194s Backend engagement/study,
18.505s AI inference/study) and each service's allocated CPU/RAM,
published Cloud Run pricing ($0.000024/vCPU-second,
$0.0000025/GiB-second, us-central1, Tier 1 -- [Google Cloud Run
pricing](https://cloud.google.com/run/pricing)):

| | Backend | AI |
|---|---|---|
| Allocated | 1 vCPU / 0.5 GiB | 4 vCPU / 2 GiB |
| vCPU-sec/study | 19.194 | 74.02 |
| GiB-sec/study | 9.597 | 37.01 |
| Cost/study | **$0.000485** | **$0.001869** |

Backend's figure includes the full synchronous wait for AI's response
(the Backend request holds the connection open, and `cpu-throttling`
is disabled, so CPU stays allocated/billed for the entire wait, not
just active processing). Frontend's per-study contribution is
negligible (a handful of small static-asset/API calls, no image
processing) and not separately modeled.

**Cold-start overhead**: not incurred during either measured batch
(zero instance starts). Stage 5B/6 established this separately: AI
cold start ~21.9-40s, Backend cold start ~20-21s -- real but
intermittent (only after ~15+ min idle), excluded from the per-study
steady-state model above and called out separately in section 22.

## 15. Cloud SQL cost model (Step 15)

Cloud SQL does not scale to zero -- **fixed regardless of study
volume**. `db-f1-micro` compute + 10 GB `PD_SSD`: consistent with this
project's own Stage 3 finding of **~$11-13/month** (compute ~$7-10/mo
+ storage ~$1.70-3/mo at published rates), re-confirmed against
current public pricing search results in this stage.

| Volume/month | SQL USD/study |
|---|---|
| 100 | $0.110 - $0.131 |
| 500 | $0.022 - $0.026 |
| 1,000 | $0.011 - $0.013 |
| 5,000 | $0.0022 - $0.0026 |

## 16. Storage cost model (Step 16)

From actual Stage 7/8/10 growth: **391,490 bytes/study**, consistently
reproduced across three independent measurement windows (Stage 7,
Stage 8, and both Stage 10 batches). Composition (from Stage 7's
object inventory): 1 input DICOM ZIP (44,326 bytes, one-time per
study) + 37 AI run-asset objects (images/arrays + their Stage-5B
per-asset metadata JSON, the bulk of the remainder).

At $0.020/GB-month ([GCS pricing](https://cloud.google.com/storage/pricing)),
**retention assumption: all data kept indefinitely** (no lifecycle
deletion policy configured):

| Cumulative studies retained | Storage/month |
|---|---|
| 100 | $0.00078 |
| 500 | $0.0039 |
| 1,000 | $0.0078 |
| 5,000 | $0.039 |

Storage cost is negligible at every modeled volume; it compounds
monthly as more studies accumulate (unlike Cloud Run, which resets
each billing period), so at very large cumulative retention (tens of
thousands of studies over years) this would eventually become
material -- not yet at the volumes considered here.

## 17. Artifact Registry (Step 17)

Current stored size (Backend + AI + Frontend images, all tags/versions
combined): **1,352.5 MB (~1.32 GB)**. At $0.10/GB-month over the first
0.5 GB free ([Artifact Registry pricing](https://cloud.google.com/artifact-registry/pricing)):
(1.32 - 0.5) x $0.10 = **~$0.082/month**. Kept distinct from runtime
study cost -- this scales with number of image builds/tags retained
across the whole project's development history, not with study volume.

## 18. Secret Manager (Step 18)

2 secrets (`pfi-dev-database-url`, `pfi-dev-jwt-secret`), **2 active
versions total** (1 destroyed version on the JWT secret, not billed).
Free tier covers the first 6 active versions/month
([Secret Manager pricing](https://cloud.google.com/secret-manager/pricing)) --
**$0/month**, not allocated to the cost model.

## 19. Total cost model (Step 19)

**A. Fixed monthly cloud cost (intended architecture, excluding the
anomaly in section 12)**:

```
Cloud SQL:            $11.00 - $13.00
Artifact Registry:    $0.08
Secret Manager:       $0.00
GCS (current ~16 MB): ~$0.0003
Cloud Run (idle, min=0 everywhere as intended): $0.00
---------------------------------------------------
TOTAL FIXED:          ~$11.08 - $13.08/month
```

**B. Variable cost/study**:

```
Backend:  $0.000485
AI:       $0.001869
Storage:  $0.0000078
---------------------------
TOTAL VARIABLE: ~$0.00236/study
```

**C. Effective cost/study at volume**:

| Volume/month | Fixed/study | + Variable | = Effective |
|---|---|---|---|
| 100 | $0.111 - $0.131 | $0.00236 | **$0.113 - $0.133** |
| 500 | $0.0222 - $0.0262 | $0.00236 | **$0.0246 - $0.0286** |
| 1,000 | $0.0111 - $0.0131 | $0.00236 | **$0.0135 - $0.0155** |
| 5,000 | $0.00222 - $0.00262 | $0.00236 | **$0.0046 - $0.0050** |

## 20. Comparison to prior estimate (Step 20)

Prior technical planning estimate (not a real billing measurement):
low $0.07, base $0.16, high $0.38 /study.

| Volume | Observed effective | vs. prior range | Classification |
|---|---|---|---|
| 100/mo | $0.113-0.133 | between low and base | **SIMILAR** |
| 500/mo | $0.025-0.029 | below low | **LOWER** |
| 1,000/mo | $0.014-0.016 | well below low | **LOWER** |
| 5,000/mo | $0.005 | far below low | **LOWER** |

**Main driver of the divergence**: Cloud Run's pay-per-compute-second
pricing is genuinely cheap even at AI's generous 4 vCPU/2 GiB
allocation, and Cloud SQL's fixed cost amortizes rapidly once volume
exceeds a few hundred studies/month -- the prior estimate's likely
implicit volume was in the "SIMILAR" 100/month range, where the two
models actually agree reasonably well.

**Important caveat, stated explicitly and carried into section 21**:
this stage's synthetic fixture uses **16x16-pixel placeholder images**
(matching the AI repo's own existing test-fixture convention), not
clinical-resolution MRI slices (typically 256x256 to 512x512+ pixels
per slice). The measured 18.5s mean AI inference time is dominated by
model/checkpoint loading and Python/PyTorch startup overhead, not
per-pixel tensor computation -- real clinical images would very
plausibly take meaningfully longer to process, which would raise the
AI variable-cost-per-study component specifically. This is why section
21 below applies an explicit safety multiplier rather than presenting
the raw measured number as the production estimate.

## 21. Financial model inputs (Step 21) -- for future Excel input, not applied yet

| Scenario | Variable cost/study | AI runtime assumption | Fixed/month |
|---|---|---|---|
| OPTIMISTIC | $0.0024 | as measured (synthetic-fixture speed) | $11.08 |
| BASE | $0.0067 | 3x measured AI inference time (real-image margin) | $12.08 |
| PESSIMISTIC | $0.020 | 10x measured AI inference time (conservative margin) | $13.08 |

These are **recommended inputs**, not applied to the Excel financial
model in this stage, per instruction. Not shown here: the cost anomaly
in section 12, which if left uncorrected would add ~$60-66/month to
the fixed side of every scenario above -- see that section for the
distinct, separately-actionable number.

## 22. Latency / performance summary (Step 22) -- engineering only, not clinical

| | Value | Source |
|---|---|---|
| Cold Backend startup | ~20-21s | Stage 6/9 measurements |
| Cold AI startup | ~21.9-40s (run-to-run variance observed) | Stage 5B/6 measurements |
| Warm upload | 680 ms mean | this stage, n=35 |
| Warm inference | 18.5s mean | this stage, n=35 |
| Warm total E2E | 19.2s mean | this stage, n=35 |
| p50 (total, warm) | 19,228 ms | this stage, n=35 |
| p95 (total, warm) | 21,812 ms | this stage, n=35 |

No clinical-performance interpretation is made from any of these
numbers.

## 23. Cost anomaly check (Step 23) -- one real finding

| Resource | Finding | Classification |
|---|---|---|
| `pfi-backend-dev-stage4h-warm` (revision) | Retired, `MinInstancesProvisioned: NotProvisioned`, `TrafficShutDown: True` -- confirmed not billing | **KEEP** (harmless, already correctly detagged from Stage 4H) |
| `pfi-backend-dev-stage4f-clean` (revision) | Same -- confirmed retired, not billing | **KEEP** |
| Artifact Registry old/untagged image layers | 1.35 GB total across all Backend/AI/Frontend builds this session; mostly within free tier | **KEEP** (negligible cost; deleting risks losing rollback images) |
| GCS objects | No duplicates found across 2 upload attempts x 35 studies (verified: `domain_durable_object_assets` count increases exactly 1:1 with study count in every batch) | **KEEP** |
| **`pfi-backend-dev` (legacy) service-level `autoscaling.knative.dev/minScale=1`** | **Live right now.** Confirmed via `gcloud run services describe` (service-level annotation, distinct from any per-revision setting) and zero "Shutdown" log entries in the last 6 hours -- an instance has been running continuously. At its 1 vCPU/512 Mi allocation, this is **~$60-66/month** of unattributed always-on compute (using the same published Cloud Run rates as section 14), separate from and larger than every other fixed-cost line in this report combined. This does not match Stage 4H's own report, which documented reverting this service's scaling to `min=0` after its warm-revision testing concluded -- it was apparently re-set to 1 at some point since. | **INVESTIGATE** -- not touched this stage (legacy Backend instructed to stay untouched across every prior stage), but flagged prominently since it is real, ongoing, and unrelated to any measured workload in this report. **UPDATE (Stage 10B, same day): RESOLVED.** See [`CLOUD_STAGE_10B_COST_ANOMALY_CLEANUP.md`](CLOUD_STAGE_10B_COST_ANOMALY_CLEANUP.md) -- minScale reverted to 0 (absent), maxScale=2 preserved, traffic/image/IAM untouched, health confirmed 200 after a real cold start. |

**No resources were deleted or modified in this stage**, per instruction.

## 24. Trial credit projection (Step 24)

No exact remaining-credit balance is queryable via CLI/API (section
1). Burn-rate **scenarios**, not a fabricated balance, using the
published rates applied above:

| Scenario | Monthly burn (if `pfi-backend-dev` anomaly is corrected to min=0) | Monthly burn (if anomaly persists) |
|---|---|---|
| Idle environment (no studies) | ~$11.08 - $13.08 | ~$71 - $79 |
| 10 studies/day (~300/mo) | ~$11.79 - $13.79 | ~$72 - $80 |
| 50 studies/day (~1,500/mo) | ~$14.62 - $16.62 | ~$75 - $83 |
| 100 studies/day (~3,000/mo) | ~$18.16 - $20.16 | ~$78 - $86 |

The anomaly in section 23, if left uncorrected, dominates every
scenario -- correcting it would cut projected monthly burn by roughly
80-85% at low-to-moderate study volume.

## 25-26. Documentation / machine-readable data

This report + `CLOUD_STAGE_10_MEASUREMENTS.csv` (35 rows: batch,
study, timestamp, success, upload_ms, inference_ms, total_ms,
study_id, run_id, error).

## 27. Remaining blockers

None blocking the Cloud technical work itself. One operational item
requiring the user's decision, not a technical blocker: whether to
revert `pfi-backend-dev`'s `minScale=1` (section 23) -- explicitly not
decided or acted on in this stage. **Resolved same-day in Stage 10B**
(see `CLOUD_STAGE_10B_COST_ANOMALY_CLEANUP.md`).

## 28. Final summary

**REAL_COST_MODEL_READY = YES** -- built from 35 real, successful
Cloud E2E runs (0 failures) across two independent, converged
measurement batches, with explicit, sourced published pricing and
explicit caveats (billing-export absence, synthetic-fixture-size
limitation, three-scenario safety margin).

**CLOUD_TECHNICAL_WORK_COMPLETE = YES** -- architecture is functional
end-to-end (Stage 7), browser target switched to Backend Green (Stage
9), persistence validated under restart/reprocess/concurrent load
(Stage 5B/7/8), and real cost/performance evidence is now captured
(this stage). No critical Cloud blocker remains. The one open item is
the section 23 cost anomaly, which is operational/cost-hygiene, not a
functional blocker -- flagged for the user's decision, not treated as
blocking closure of the Cloud technical work.
