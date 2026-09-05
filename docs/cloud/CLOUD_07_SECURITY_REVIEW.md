# CLOUD-07 — Security Review

## Permissions actually used this stage

**None new.** All Phase 0 audit calls (Cloud Run revisions/traffic, Logging/Monitoring API state,
notification channels/alert policies/uptime checks/dashboards, `terraform plan` against real DEV
state) used the operator's own existing `gcloud` authentication — no workflow ran, no WIF exchange
happened, no new IAM binding was created or used.

## Three separated roles for rollback (Phase 15's explicit ask)

CLOUD-06 already proposed a `pfi-github-ci-reader` (viewer-only) identity for read-only checks and a
separate Terraform-plan identity. CLOUD-07 adds one more, for the eventual real-execute path:

| Role | Identity (proposed, none created) | Permissions | Used by |
|---|---|---|---|
| **Read-only observer** | `pfi-github-ci-reader` (from CLOUD-06) | `roles/run.viewer`, `roles/artifactregistry.reader` | `cloud-06-validate-dev.yml`, `cloud-07-rollback.yml`'s evaluation steps (revision check, health check) |
| **Deployment/promoter** | The proposed Terraform-plan identity (from CLOUD-06) | `roles/run.viewer`, `roles/iam.serviceAccountViewer`, `roles/artifactregistry.reader`, `roles/cloudsql.viewer` — all viewer-only, no deploy capability yet either (CLOUD-06 also stops before apply) | `cloud-06-promote-demo.yml`'s `terraform plan` step |
| **Rollback executor** (NEW, CLOUD-07) | `pfi-github-rollback-executor` (proposed, not created) | `roles/run.developer` scoped to a specific `run.services.update` / traffic-management permission — **not** the whole `run.developer` role if a narrower custom role can express just "update traffic on an existing revision", which is preferable and should be the actual grant once this is authorized; documented here as the target shape | The eventual real traffic-shift step in `cloud-07-rollback.yml` — does not exist yet |

None of the three is `Editor` or `Owner`. None is project-wide beyond what Cloud Run's own IAM model
requires (its finest-grained unit is the service, and `run.developer`/`run.viewer` are already
resource-scoped when bound at the service level rather than the project level — the actual grant,
when authorized, should be scoped to `pfi-backend-dev`/`pfi-frontend-dev`/`pfi-backend-demo`/
`pfi-frontend-demo` specifically, not the project).

## Why a rollback executor must never also be a promoter

Separating "can plan/promote a new release" from "can roll back traffic" means a compromised or
buggy promotion workflow run cannot also silently revert a legitimate, intentional deploy, and vice
versa — a compromised rollback workflow can only move traffic between revisions that already exist
and were already validated by the promoter identity at some point, never introduce a new,
unvalidated image. This is the same least-privilege reasoning CLOUD-06 applied to keep the reader and
promoter identities apart.

## `cloud-07-rollback.yml` permissions block

```yaml
permissions:
  contents: read
  id-token: write
```

No `contents: write`, no `actions: write` beyond what `actions/upload-artifact` needs (already covered
by the default `GITHUB_TOKEN` scope for uploading the workflow's own artifacts, no extra permission
requested for it).

## No JSON keys, no Owner/Editor — anywhere in this design

Consistent with every prior CLOUD stage: WIF only, viewer/narrow-role identities only. The rollback
executor identity, once actually created and used, remains the narrowest of the three — it needs
exactly one capability (traffic reassignment on an already-deployed revision) and nothing else.

## Verdict

No security finding blocks this design. One new identity is proposed (`pfi-github-rollback-executor`,
not created), alongside the two CLOUD-06 already proposed — none created this stage, all viewer-only
or narrowly-scoped-write, all WIF-authenticated.
