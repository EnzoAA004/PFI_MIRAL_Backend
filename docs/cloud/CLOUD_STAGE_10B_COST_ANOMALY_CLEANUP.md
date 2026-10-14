# Cloud Stage 10B — Cost Anomaly Cleanup (2026-09-23)

Goal: remove the unintended always-on scaling found on the legacy
Backend in Stage 10, without altering any functional architecture.

**Result: resolved.** Single, narrowly-scoped change: legacy
`pfi-backend-dev`'s service-level `minScale` reverted from `1` to
absent (effective `0`). Nothing else touched.

## 1. Verify (Step 1)

Confirmed live, before any change:

```
traffic: pfi-backend-dev-00002-jwd = 100%
annotations: autoscaling.knative.dev/maxScale=2;
             autoscaling.knative.dev/minScale=1;
             run.googleapis.com/cpu-throttling=false;
             run.googleapis.com/execution-environment=gen2
```

## 2. Change (Step 2)

```
gcloud run services update pfi-backend-dev \
  --project=pfi-asplanatti-fabrello-v1 --region=us-central1 \
  --min-instances=0
```

Only `--min-instances` was passed -- no image, traffic, env-var, or
IAM flag included in this command.

## 3. Verify config (Step 3)

```
traffic: pfi-backend-dev-00002-jwd = 100%   (unchanged)
annotations: autoscaling.knative.dev/maxScale=2;
             (minScale absent -> effective 0)
image: ...backend@sha256:39ae4cdc31693cf323e2485acd9fc3b0d27463a5bb3105cd945ccdd72368c068 (unchanged)
```

The `gcloud` CLI's own deploy-progress output briefly referenced
`pfi-backend-dev-stage4h-warm` (an older, already-retired revision
name) in its human-readable status line -- a red flag worth checking
immediately, so a direct `gcloud run services describe` was run right
after to confirm the actual live state. It confirmed traffic, image,
and every other setting were exactly as before -- only the scaling
annotation changed, as intended.

## 4. Health (Step 4)

```
GET https://pfi-backend-dev-7cxg46zwea-uc.a.run.app/api/system/health
  -> 200 {"status":"ok"}, 20,954 ms (one real cold start, expected and acceptable)
  -> immediate retry: 200, 248 ms (warm)
```

## 5. Cost consequence (Step 5)

- **Before**: `minScale=1` on a 1 vCPU / 512 Mi service, continuously
  running (confirmed via zero "Shutdown" log entries over 6+ hours
  prior) -- an estimated ~$60-66/month of always-on compute at
  published Cloud Run rates (Stage 10, section 23).
- **After**: `minScale=0` (absent) -- the instance is now eligible to
  scale to zero during idle periods, same as every other service in
  this architecture (Backend Green, AI, Frontend all already `min=0`).
- **Not claimed**: an exact dollar savings figure. No billing export
  is configured in this project (Stage 10, section 1), so actual
  avoided spend cannot be confirmed from billing data; only the
  configuration change and its expected effect (idle compute no
  longer continuously billed) are stated.

## 6. Scope confirmation

Nothing else was touched: legacy Backend was not deleted, its image
was not changed, traffic stayed at `00002-jwd=100%`, no env vars were
modified, no IAM change was made, and Backend Green / AI / Frontend
were not touched at all in this stage.

## 7. Final summary

| Check | Result |
|---|---|
| Previous `minScale` | 1 |
| New `minScale` | 0 (absent) |
| `maxScale` | 2 (unchanged) |
| Traffic | `pfi-backend-dev-00002-jwd=100%` (unchanged) |
| Image | unchanged (same digest) |
| Health after change | 200 (cold, 21s) then 200 (warm, 0.25s) |
| Scope | scaling only -- no other setting touched |

**COST_ANOMALY_RESOLVED = YES**
