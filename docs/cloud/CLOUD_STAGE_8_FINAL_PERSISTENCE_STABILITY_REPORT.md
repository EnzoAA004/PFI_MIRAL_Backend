# Cloud Stage 8 — Final Persistence Stability (2026-09-23)

Goal: a short final durability/stability validation before the
Frontend switch and cost measurement, reusing the Stage 7 synthetic
study/run. No architecture changes, no new tables, no scope expansion.

**Result: PASS.** The retained Stage 7 study survived 3+ days,
21 total reprocess calls (5 sequential + 10 concurrent + 1 post-restart
+ Stage 7's own), a full concurrent mixed-load burst, and one AI
service restart -- with zero duplication, zero unexpected errors, and
byte-identical checksums throughout.

## 1. Baseline (Step 1)

- Backend Green health: 200. AI authenticated: 200. AI unauthenticated: 403.
- Cloud SQL (`pfi-postgres-dev`): `RUNNABLE`.
- Bucket (`pfi-backend-durable-dev`): reachable.
- Revisions at start: Backend `pfi-backend-v2-dev-00003-8xf`, AI `pfi-ai-module-00007-km7`.

## 2. Retained study check (Step 2) -- no re-upload

```
GET /api/ai/assets/6c60404bdca1a206/sagittal/overlay.png -> 200
  sha256 = fbf6a95d618dd906d92f27baecd9b856ea22879c1d914706ac6d3184d9ed8298 (exact match to Stage 7)
GET /api/ai/assets/6c60404bdca1a206/sagittal/input.png -> 200
  sha256 = 55f74d109b525f483f8551235b1688eb1689591bef575a07ad6d2c231ed8fc3f (exact match to Stage 7)
```

GCS input object still present (44,326 bytes, unchanged). Postgres
`domain_durable_object_assets` row for `study_id=073f44ab7d1945579d570a9e91d3540f`
still present, unchanged.

## 3. Reprocess loop (Step 3) -- 5/5 PASS

| Iteration | Status | Latency | Series found |
|---|---|---|---|
| 1 | 200 | 5,885 ms | 5 |
| 2 | 200 | 723 ms | 5 |
| 3 | 200 | 683 ms | 5 |
| 4 | 200 | 678 ms | 5 |
| 5 | 200 | 649 ms | 5 |

First call slower (connection/token warmup); subsequent four
consistently ~650-720 ms. Zero unexpected 5xx, zero timeouts.
**5/5 controlled success.**

## 4. Concurrent load (Step 4)

40 requests, 5 workers, mixed evenly across health / asset retrieval
(overlay.png) / asset retrieval (input.png) / reprocess (10 of each):

```
STATUS SUMMARY: {200: 40}
latency min=0.223s p50=0.309s p95=0.703s max=0.714s
window: 14:30:26Z - 14:30:29Z (3 seconds wall-clock)
  health:        10/10 -> 200
  asset_overlay: 10/10 -> 200
  asset_input:   10/10 -> 200
  reprocess:     10/10 -> 200
```

**40/40 success, 0 expected 4xx, 0 unexpected 5xx, 0 timeouts.**

## 5. Duplication / storage check (Step 5) -- PASS

After 16 cumulative `upload.study.reprocessed` events (5 sequential +
10 concurrent + 1 from Stage 7):

```
domain_durable_object_assets total rows:            1  (unchanged)
domain_durable_object_assets rows for this study:    1  (unchanged)
GCS bucket total size:                    1,374,116 bytes (unchanged from post-Stage-7)
studies/ prefix object count:                        1  (unchanged)
```

**Original input is reused on every reprocess call, never
re-uploaded.** No new AI output objects were created either --
reprocess only re-runs classification (`/inputs/study`), not the
inference pipeline, so no new `runs/...` assets were expected and none
appeared. No orphan objects found.

## 6. Memory loss check (Step 6) -- one restart, PASS

Forced one fresh AI revision (`pfi-ai-module-00008-gww`), per
instruction ("no long idle wait needed", "do not repeat multiple
restart cycles"; Backend's own restart durability was already proven
in Stage 7, so only AI was restarted here):

```
GET /api/ai/assets/6c60404bdca1a206/sagittal/overlay.png -> 200
  sha256 = fbf6a95d618dd906d92f27baecd9b856ea22879c1d914706ac6d3184d9ed8298 (still exact match)
POST /api/ai/studies/073f44ab7d1945579d570a9e91d3540f/reprocess -> 200 (clean, 5 series)
```

## 7. Failure regression (Step 7)

```
Malformed (non-ZIP) upload  -> 400 BAD_REQUEST, controlled message
Disallowed asset-name pattern -> 403 ACCESS_DENIED (asset name itself
  not in the allowlist -- a different but still controlled error)
Unknown run_id, valid asset-name pattern -> 404 NOT_FOUND (the
  genuine "doesn't exist" case)
```

Zero 500/502 across all three cases.

## 8. Persistence classification (Step 8)

| Component | Durable? | Storage location | Survives restart? | Required for reprocess? | Regenerable? |
|---|---|---|---|---|---|
| INPUT_DICOM | YES | GCS `studies/{studyId}/inputs/{assetId}/study.zip` | YES (proven, Stage 7 + this stage) | YES -- the entire mechanism | NO (original upload; loss means data loss) |
| SQL_METADATA (`domain_durable_object_assets`) | YES | Cloud SQL `pfi` | YES | YES -- points reprocess/rehydration at the right object key | Partially -- object key is deterministic but the row itself is the only pointer to it |
| AUDIT_EVENTS (`domain_audit_events`) | YES | Cloud SQL `pfi` | YES | NO | N/A -- history, not functional state |
| AI_OUTPUTS (masks/overlays/slices) | YES | GCS `runs/{runId}/{plane}/...` | YES (Stage 5B + Stage 7 proof) | NO for reprocess itself, YES for reviewing a prior result | YES -- reprocess/pipeline-run regenerates them from the durable input |
| AI_METADATA (per-asset `_meta/*.json`) | YES | GCS `runs/{runId}/{plane}/_meta/*.json` | YES (this stage's restart proof) | YES -- required for AI-side rehydration (Stage 5B fix) | YES -- regenerated together with its asset |
| CACHE (AI's local GCS materialize cache) | NO (by design) | Local container disk, ephemeral | NO -- and correctly so | NO -- only a performance optimization | YES -- always re-materializable from the durable GCS copy |

## 9. `domain_studies` / `domain_ai_analysis_runs` finding (Step 9)

Confirmed again this stage: both `domain_studies` (0 rows throughout)
and `domain_ai_analysis_runs` (0 rows throughout) have working
schema/repository code but are not called from any live controller
path. The product's actual, currently-functioning persistence for
study/run identity is `domain_durable_object_assets` +
`domain_audit_events`, both of which are proven durable and correct
in this and the prior stage.

**Classification: NON_BLOCKING_ARCHITECTURAL_DEBT.** Current product
behavior (upload, inference, retrieval, reprocess, restart durability)
is fully functional end-to-end through the mechanisms actually in use.
Not fixed this stage, per explicit instruction.

## 10. Performance snapshot (Step 10) -- snapshot only, no cost math

| | Value |
|---|---|
| Backend CPU/RAM | 1 vCPU / 512 Mi |
| Backend requests this stage | ~46 (5 reprocess + 40 concurrent + a few health/verification calls) |
| Backend latency (concurrent burst) | p50 0.309s, p95 0.703s, max 0.714s |
| Backend Hikari pool | `totalConnections=2, active=0, idle=2, waiting=0` |
| AI CPU/RAM | 4 vCPU / 2 Gi |
| AI concurrency/scaling | concurrency=1, min=0, max=1 (unchanged) |
| Cloud SQL | `RUNNABLE`, 3 active connections to `pfi`, DB size 9,319 kB |
| GCS total (all synthetic test data to date) | 1,374,116 bytes, ~76 objects (1 input + 37 AI assets + 37 AI metadata + 1 legacy from earlier stages) |

## 11. Cleanup decision (Step 11)

**No cleanup needed.** Section 5 already confirmed zero duplicates,
zero orphans, and zero unnecessary growth across 16 reprocess calls.
The single canonical synthetic study/run
(`studyId=073f44ab7d1945579d570a9e91d3540f`, `runId=6c60404bdca1a206`)
is retained unchanged for Stage 10 cost testing.

## 12. Documentation / Git

This report + an update to `CLOUD_REAL_DEPLOYMENT_EXECUTION_PLAN.md`
marking Stage 8 status, committed docs-only.

## 13. Final summary

| Check | Result |
|---|---|
| Baseline | PASS |
| Retained study/asset retrieval, no re-upload | PASS, checksums exact |
| 5x sequential reprocess | 5/5 PASS |
| 40-request concurrent mixed load | 40/40 PASS, p95=0.703s |
| Duplication/storage check | PASS -- zero duplication across 16 reprocess calls |
| Restart/fresh-process check (AI) | PASS, checksum exact, reprocess clean |
| Failure regression | PASS -- 400/403/404, zero 500/502 |
| Persistence classification | Complete, 6 components classified |
| `domain_studies`/`domain_ai_analysis_runs` | NON_BLOCKING_ARCHITECTURAL_DEBT, documented, not fixed |
| Performance snapshot | Captured, no cost math performed |
| Cleanup | Nothing to clean; canonical data retained |

**FINAL_PERSISTENCE_STABLE = YES**
**READY_FOR_FRONTEND_SWITCH = YES**
**READY_FOR_COST_MEASUREMENT = YES**
