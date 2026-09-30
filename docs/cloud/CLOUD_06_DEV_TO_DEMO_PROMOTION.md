# CLOUD-06 — DEV → DEMO Promotion Pipeline

**Status: EXPERIMENTAL / PLAN-ONLY. No GCP resource created. New cost: $0.** This stage designs and
validates (locally, read-only) the automation for promoting an already-DEV-validated image to DEMO —
it does not deploy DEMO, does not create Cloud SQL/Secret Manager for DEMO, and does not touch any
existing GCP resource.

## Flow designed

```
GitHub → CI green → Artifact Registry SHA image → DEV → smoke tests →
manual approval → DEMO → same immutable image → post-deploy smoke → rollback-ready
```

No rebuild ever happens between DEV and DEMO — the same tag and digest validated in DEV is the only
thing DEMO is ever allowed to receive (enforced by `scripts/cloud/verify-image-digest.sh`, Phase 7).

## Phase 0 — Audit findings (read-only)

- **Workflows**: `.github/workflows/backend-ci.yml` (test/build validation) and
  `cloud-03-publish-image.yml` (WIF-based image publish, `push`-triggered on the isolated CLOUD-03
  branch + `workflow_dispatch`). No promotion/deploy workflow existed before CLOUD-06.
- **Terraform**: `environments/dev` is live and applied (CLOUD-04); `environments/demo` existed since
  CLOUD-01 but was **never applied** and had several real, previously-undetected bugs (see "Demo
  Terraform fixes" below) — this is the first time it was actually planned against live GCP state.
- **Images**: `backend:dee0826c9e1121e348192058acde841b6f933608` and
  `frontend:1c77231e2e1833bada6dc1c0f4f622f1d32e3470` are the current DEV-validated digests (no AI
  image published yet, per CLOUD-03's deferred AI publish).
- **GitHub repo variables** (read via REST API, read-only): `GCP_WIF_PROVIDER`,
  `GCP_ARTIFACT_PUSH_SERVICE_ACCOUNT`, `GCP_ARTIFACT_REPOSITORY`, `GCP_PROJECT_ID`, `GCP_REGION` — all
  from CLOUD-03. No CI-reader or Terraform-plan identity/variable exists yet (see
  `CLOUD_06_SECURITY_REVIEW.md`).
- **Cloud branches**: `cloud/cloud-00` through `cloud/cloud-05-managed-persistence` (all pushed, none
  merged to `main`). CLOUD-06 branches created from CLOUD-05's final pushed HEAD (`b456333`).
- **Cloud Run DEV state**: `pfi-backend-dev` and `pfi-frontend-dev` live, confirmed via CLOUD-04's own
  apply history — used as the read target for `cloud-06-validate-dev.yml`.
- **GitHub Environments**: **zero exist** on the Backend repo (`GET /repos/.../environments` → empty)
  — confirmed via the GitHub REST API, read-only, no environment created.

## Demo Terraform fixes (found during Phase 5, corrected)

`environments/demo/main.tf` predates CLOUD-04 and was never updated to match its fixes to `dev`. Four
real bugs, all now fixed (see `CLOUD_06_CHANGE_PLAN.md` for the resulting clean plan):

1. `health_check_path = "/actuator/health"` — not a real endpoint (same CLOUD-01 finding CLOUD-04
   already fixed for DEV); corrected to `/api/system/health`.
2. Frontend had no `port`/`cpu_idle` set — would have hit the exact same "memory < 512Mi requires
   cpu_idle" admission-control rejection CLOUD-04 found for DEV; fixed (`port = 80`,
   `cpu_idle = true`).
3. `module.ai_cloud_run` had **no `count` gate at all** — an apply would have unconditionally created
   a dedicated DEMO AI Cloud Run service regardless of any flag. Fixed to mirror DEV's
   `count = var.enable_ai_service ? 1 : 0` pattern.
4. `project_iam_bindings` granted `roles/cloudsql.client` **unconditionally**, even with Cloud SQL
   off. Fixed to gate behind `var.enable_cloud_sql`, mirroring DEV.

Also added: `enable_secrets`, `enable_ai_service`, `backend_min_instances`, `frontend_min_instances`
variables (none existed before), and the same persistence/secret/sidecar `locals` pattern DEV's
CLOUD-05 design uses, so DEV and DEMO stay structurally identical — only flags and the image digest
should ever differ between them.

## Phase 4 — GitHub Environment design (not created)

A GitHub Environment named `demo` is designed for future use as the approval gate between DEV
validation and DEMO promotion:

- **Required reviewers**: the project owner (repo admin) — the only person authorized to approve a
  DEMO promotion, matching this project's single-maintainer thesis structure.
- **Environment secrets/variables**: **none planned to hold GCP credentials** — WIF remains the
  authentication strategy (no JSON key, no long-lived secret to scope to an environment). Any
  environment-scoped variable would only be non-secret config (e.g. a DEMO-specific timeout), not
  credentials.
- **Approval gate**: `cloud-06-promote-demo.yml`'s job would declare `environment: demo` once the
  environment exists, so GitHub itself blocks the job from proceeding past checkout until the
  required reviewer approves the run — no custom approval logic needed in the workflow.
- **Not created this stage**: creating a GitHub Environment via the API is a write action; CLOUD-06's
  explicit scope is read-only against GitHub and GCP alike. The workflow files reference `environment:
  demo` in a comment, not as an active `environment:` key, so they remain valid and runnable (once
  their GCP identities exist) without the environment being created first.

## Phase 10 — Failure policy

| Failure | Response |
|---|---|
| CI fails | No release manifest is generated — `cloud-06-validate-dev.yml` never runs without a passing CI build's image existing first. |
| DEV smoke fails | `cloud-06-validate-dev.yml` fails outright (`set -euo pipefail`, no `continue-on-error`) — no manifest is uploaded, so `cloud-06-promote-demo.yml` has nothing valid to consume. |
| Digest mismatch | `verify-image-digest.sh` exits non-zero — `cloud-06-promote-demo.yml` fails before reaching `terraform plan`. |
| DEMO plan shows unexpected change/destroy/replace | Per Phase 6's explicit gate: STOP, do not proceed, document the exact diff (see `CLOUD_06_CHANGE_PLAN.md` for what a *clean* plan looks like, to compare against). |
| Post-deploy smoke fails (once DEMO is real) | Rollback design (`CLOUD_06_ROLLBACK_DESIGN.md`, `cloud-06-rollback-demo.yml`) — traffic back to the last known-good revision. Not executable yet (no DEMO apply exists to roll back). |

No step in any CLOUD-06 workflow uses `continue-on-error: true` — every failure stops the run.

## Documents in this set

- `CLOUD_06_RELEASE_MANIFEST.md` — the `pfi-cloud-release.v1` schema and example.
- `CLOUD_06_ROLLBACK_DESIGN.md` — full rollback strategy detail.
- `CLOUD_06_COST_CHECK.md` — DEMO cost model (min_instances=0 vs 1), $0 new cost this stage.
- `CLOUD_06_SECURITY_REVIEW.md` — IAM/permissions design, including the two new identities proposed
  but not created.
- `CLOUD_06_CHANGE_PLAN.md` — the DEMO `terraform plan` result and what workflows/scripts were
  validated, and how.

## Explicitly out of scope / not executed this pass

No `terraform apply`, `terraform destroy`, or `terraform import`. No `gcloud services enable`,
`gcloud run deploy`, `gcloud run services update`, IAM write, secret creation, or Cloud SQL creation.
No GitHub Environment created. No Artifact Registry push. No monitoring, DNS, or GPU resource. No
existing GCP resource touched. **New cost incurred: $0.**
