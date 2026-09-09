# CLOUD-08 — Cost Envelope (consolidated, no new spend)

Every figure below is sourced from the per-stage cost checks already produced (`CLOUD_04_COST_CHECK.md`
through `CLOUD_07_COST_CHECK.md`) — this document only consolidates and separates them, it does not
introduce new pricing claims.

## CURRENT REAL INCREMENTAL COST (what's actually running today)

**No real invoice/billing export was consulted to produce this section — these are cost-model
estimates against known pricing and known resource shapes, not a confirmed bill.**

| Resource | Monthly run-rate |
|---|---|
| `pfi-backend-dev`, `pfi-frontend-dev` (`min_instance_count=0`, scale-to-zero) | ~$0 at current (smoke-test-level) traffic — not literally zero if traffic grows, but no idle charge either way |
| WIF pool/provider/SA/IAM bindings | $0 (no charge for any IAM/WIF resource) |
| Artifact Registry storage (2 SHA-tagged image sets: Backend 354MB + Frontend 75.5MB, ×3 manifests each per the Buildx structure) | low cents/month — well under $0.50 given the 0.5GB free allowance and current total repo size (433.7MB pre-existing + these two pushes) |
| `pfi-ai-module` (pre-existing, unrelated) | ~$0 idle (scale-to-zero, no `minScale`) |
| **Total current real incremental cost** | **approximately $0 / very low under current usage**, driven by `min_instances=0` on both Cloud Run services and a small Artifact Registry footprint — not a literal, confirmed $0 |

**No new cost was introduced by CLOUD-05, CLOUD-06, CLOUD-07, or CLOUD-08** — all four remain either
fully unapplied (05/06/07: 0 resources created) or documentation-only (08: no `.tf`/workflow file
touched). Everything cost-bearing above (Cloud Run DEV, Artifact Registry storage) predates and is
unchanged by those four stages.

## FUTURE DEV COST (if CLOUD-05 is applied to DEV)

| Resource | Monthly run-rate |
|---|---|
| Cloud SQL `db-f1-micro` (compute + 10GB storage) | **~$9.37/month** (sourced, live-priced: $7.665 compute + $1.70 storage) |
| Secret Manager (2 secrets, 1 version each) | $0 (within free tier: 6 versions/month free) |
| Everything already in "current real cost" | unchanged |
| **Total future DEV cost** | **~$9.37/month incremental** |

## FUTURE DEMO COST (if CLOUD-06 is applied)

| Resource | Monthly run-rate |
|---|---|
| `pfi-backend-demo`, `pfi-frontend-demo` (scale-to-zero, `min=0`) | ~$0 idle, same shape as DEV |
| `pfi-backend-demo`, `pfi-frontend-demo` (`min=1`, kept warm for a live demo) | Backend (`cpu_idle=false`): ~$0.0000190/s ≈ **$49.25/month** if kept warm 30 days straight; Frontend (`cpu_idle=true`): ~$0.0000031/s ≈ **$8.13/month** if kept warm 30 days straight |
| **Total future DEMO cost, `min=0`** | **~$0/month** (recommended when not actively demoing) |
| **Total future DEMO cost, `min=1`** | **~$57.38/month** if left warm continuously — **not the intended usage pattern**; intended pattern is warm only for the hours around an actual demo session, not continuously |

## FUTURE MONITORING COST (if CLOUD-07 is applied)

| Resource | Monthly run-rate |
|---|---|
| Uptime check (per environment, 300s interval, single region) | **~$2.63/month** (unresolved multi-region caveat could increase this — see runbook) |
| Alert policies (Backend 5xx, Frontend 5xx, uptime-failure) | $0 (built-in metrics) |
| Notification channel (email) | $0 |
| **Total future monitoring cost, per environment** | **~$2.63/month** |

## FUTURE STORAGE COST

No new Cloud Storage bucket or usage is proposed by CLOUD-05/06/07 — the existing
`pfi-rm-lumbar-artifacts-2026-ef` bucket stays untouched and unused by any of this session's designs.
**$0 incremental.**

## OPTIONAL AI COST (not authorized, informational only)

| Item | Note |
|---|---|
| AI image push (2.12 GB) | Storage-only cost, ~$0.21/month per un-cleaned push — still pending separate authorization, not part of this envelope's totals |
| Real AI Cloud Run service | Would follow the same scale-to-zero shape as Backend/Frontend if CPU-only; a real Qwen LLM would need a GPU, still fully blocked (EXT-06) |

## Consolidated scenarios

| Scenario | What's running | Estimated monthly cost |
|---|---|---|
| **MINIMUM DEMO DAY** (spin up DEMO with `min=0`, demo for a few hours, spin back to `min=0` after) | DEV always-on (scale-to-zero) + DEMO scale-to-zero + Cloud SQL if CLOUD-05 applied | ~$9.37/month (Cloud SQL is the only always-on cost; everything else is near-$0 with occasional demo-hour blips) |
| **7-DAY VALIDATION WINDOW** (CLOUD-05 applied, DEMO exists at `min=0`, monitoring on both envs) | Cloud SQL (7 days ≈ $2.16) + monitoring ×2 envs (7 days ≈ $0.42 combined) + Cloud Run near-$0 | **~$2.6/month-equivalent for the week** (≈ $0.37/day) |
| **30-DAY FULL STACK** (CLOUD-05+06+07 all applied, DEV+DEMO both `min=0`, monitoring on both) | Cloud SQL ~$9.37 + monitoring ×2 ~$5.26 + Cloud Run near-$0 | **~$14.63/month** |
| **30-DAY FULL STACK + DEMO kept warm continuously** | Above + DEMO `min=1` for the full month | **~$72.01/month** — the scenario to actively avoid; DEMO should be `min=0` except around real demo sessions |

No cost in any scenario above has been incurred — CLOUD-05/06/07 remain unapplied.

## Shutdown / cost-reduction plan (design only, not executed)

- **Cloud Run**: `min_instances=0` on every environment/service by default (already true for DEV
  today; DEMO's default should match unless a demo is imminent).
- **Cloud SQL**: `gcloud sql instances patch pfi-postgres-dev --activation-policy=NEVER` stops
  compute billing (drops to ~$1.70/month storage-only) without deleting data — the reversible lever
  for a project not run 24/7. Full deletion (`gcloud sql instances delete`) is a separate, harder
  decision, never bundled into a routine "stop" action.
- **DEMO disable/remove**: `terraform destroy -target=module.backend_cloud_run -target=module.frontend_cloud_run`
  scoped to `environments/demo` removes DEMO's two services without touching DEV or any shared
  platform resource (WIF, Artifact Registry) — cleanly reversible by re-running the promotion.
- **Monitoring disable**: flip `enable_monitoring=false` back — removes the uptime check (its
  ongoing cost) and alert policies; no data loss since these are live-signal resources, not
  historical stores.
- **Log retention**: default 30-day retention stays free; nothing in this design proposes extended
  retention — no action needed to avoid a retention-driven cost.
- **Artifact cleanup**: no cleanup policy applied yet (see `CLOUD_03_SECURITY_REVIEW.md`'s
  retention-policy design) — old SHA-tagged images accumulate until that's separately authorized;
  not urgent at 2 images' current total size.
- **State preservation**: none of the above shutdown actions touch Terraform state files — stopping
  or scaling down a resource is a live-config change, not a `terraform destroy` of the resource
  definition (except the explicit DEMO-removal case above, which is a deliberate `destroy -target`).
