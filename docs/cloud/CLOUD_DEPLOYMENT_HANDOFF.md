# Cloud Deployment Handoff

Read this file first in any future session that picks up cloud deployment work. It tells you
exactly where things stand, right now, without needing to re-derive anything from git history.

## Current real state (as of this freeze, 2026-09-05)

- **DEV is live**: `pfi-backend-dev` and `pfi-frontend-dev` are real, running Cloud Run services,
  smoke-tested including a real browser check with CORS working.
- **DEMO does not exist.** No Cloud SQL exists. No Secret Manager secret exists. No monitoring
  alert/uptime-check exists. `pfi-ai-module` (pre-existing, unrelated to this session) is the only
  other Cloud Run service.
- **Everything past DEV is designed, coded, and plan-validated (real `terraform plan` against live
  GCP for every stage) but not applied.**

## Exact SHAs (verify these are still current before trusting them — `git fetch` first)

| Repo | Branch | SHA |
|---|---|---|
| Backend | `cloud/cloud-03-artifact-wif-backend` | `dee0826c9e1121e348192058acde841b6f933608` |
| Backend | `cloud/cloud-04-cloudrun-backend` | `8ac7c0f50853cd458ad2d5a4756a8313587cf008` |
| Backend | `cloud/cloud-05-managed-persistence` | `b456333890902f41e9579c23216fb1c39f53886d` |
| Backend | `cloud/cloud-06-promotion-backend` | `d05c85a96ee19529594b47ee35978518ec5b8c6a` |
| Backend | `cloud/cloud-07-observability-rollback` | `1da60ca2ba7a52352c4bf71aaaaa32e075141c4e` |
| Frontend | `cloud/cloud-03-artifact-wif-frontend` | `1c77231e2e1833bada6dc1c0f4f622f1d32e3470` |
| AI | `cloud/cloud-03-artifact-wif-ai` | `95f1ca1828fef3f49f6e05bd0103d00820525452` |

## Exact images (immutable, already pushed)

- `us-central1-docker.pkg.dev/pfi-asplanatti-fabrello-v1/pfi-containers/backend:dee0826c9e1121e348192058acde841b6f933608`
- `us-central1-docker.pkg.dev/pfi-asplanatti-fabrello-v1/pfi-containers/frontend:1c77231e2e1833bada6dc1c0f4f622f1d32e3470`
- AI: **not pushed**. `AI_IMAGE_PUSH_PENDING_APPROVAL` — needs its own explicit yes, separate from
  Backend/Frontend, because of its 2.12 GB size (see `CLOUD_03_COST_CHECK.md`).

## Exact DEV URLs

Retrieve fresh via `gcloud run services describe pfi-backend-dev --region=us-central1
--format="value(status.url)"` (and `pfi-frontend-dev`) — not hardcoded here since Cloud Run URLs
are stable but re-verifying costs nothing and avoids trusting a stale copy.

## Exact pending resources (none created)

Cloud SQL `pfi-postgres-dev` (`db-f1-micro`), 2 Secret Manager containers
(`pfi-dev-database-url`, `pfi-dev-jwt-secret`), DEMO's `pfi-backend-demo` +
`pfi-frontend-demo` + their runtime SA, 4 monitoring resources per environment.

## Exact costs (see `CLOUD_08_COST_ENVELOPE.md` for full detail)

- Current: ~$0/month.
- If CLOUD-05 applied: +~$9.37/month (Cloud SQL, always-on).
- If CLOUD-06 applied, DEMO at `min=0`: +~$0/month idle.
- If CLOUD-07 applied, per environment: +~$2.63/month.
- Full stack, 30 days, DEMO `min=0`: **~$14.63/month**.

## Exact gates (see `CLOUD_08_DEPLOYMENT_RUNBOOK.md` for full detail)

Gate A (cost) → B (API enable) → C (Cloud SQL apply) → D (secret creation) → E (Backend revision) →
F (DEMO creation) → G (monitoring cost) → H (final E2E). Each needs its own explicit human
approval — none carries forward from this freeze.

## Exact next command to run (if resuming CLOUD-05)

```bash
# 1. Re-sync first — don't trust this file's SHAs blindly:
git -C PFI_MVPTest_Enzo_Backend fetch --all --prune
git -C PFI_MVPTest_Enzo_Backend_cloud05 status --short   # must be clean
git -C PFI_MVPTest_Enzo_Backend_cloud05 log --oneline -1 # must show b456333...

# 2. Re-authenticate (ADC likely expired since this freeze):
gcloud auth application-default login
gcloud auth application-default print-access-token > /dev/null && echo OK

# 3. Re-plan FRESH — never reuse a plan file from before:
cd infra/terraform/environments/dev
terraform init
terraform validate
terraform plan -var="enable_cloud_sql=true" -var="enable_secrets=true" \
  -var="backend_image=.../backend:dee0826c9e1121e348192058acde841b6f933608" \
  -var="frontend_image=.../frontend:1c77231e2e1833bada6dc1c0f4f622f1d32e3470" \
  -var="ai_image=.../backend:unused"

# 4. STOP HERE. Compare the fresh plan against CLOUD_05_CHANGE_PLAN.md's "7 to add, 1 to change, 0
#    to destroy" shape. If it matches and cost (Gate A/C) is approved, only then proceed to apply.
```

## Explicit STOP points (do not proceed past these without a fresh, explicit approval)

- **STOP before enabling any API** not already enabled (`sqladmin.googleapis.com`,
  `secretmanager.googleapis.com`) — Gate B.
- **STOP before any `terraform apply`** — every apply in this project so far has been from a
  reviewed, saved plan file, applied verbatim; never `apply` a plan you haven't personally read.
- **STOP if a fresh plan's shape differs at all** from what's documented here (different resource
  count, any `change`/`destroy`/`replace` not previously seen) — investigate before proceeding, per
  every prior stage's STOP-condition pattern in this project.
- **STOP before pushing the AI image** — needs its own separate authorization regardless of
  Backend/Frontend's status.
- **STOP before running any CLOUD-06/07 GitHub Actions workflow for real** — they exist but have
  never been executed; the rollback executor (CLOUD-07) specifically has no IAM grant yet and
  cannot function until that's deliberately added.
