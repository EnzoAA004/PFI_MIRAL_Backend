# CLOUD-08 — Change Plan (freeze-preparation only)

**No `terraform apply`, no `gcloud` write, no new resource, no new cost from this stage.** CLOUD-08
itself produces documentation only — the Terraform code it validates already exists from
CLOUD-01/03/04/05/06/07, none of it modified here.

## Terraform validation (this stage, on the CLOUD-08 worktree)

```
$ terraform fmt -check -recursive .
(exit 0 — nothing to reformat)

$ cd platform/identity && terraform init && terraform validate
Success! The configuration is valid.

$ cd environments/dev && terraform init && terraform validate
Success! The configuration is valid.

$ cd environments/demo && terraform init && terraform validate
Success! The configuration is valid.
```

No fresh `terraform plan` was regenerated this stage — the plan shapes for CLOUD-05/06/07 were
already obtained as real plans against live GCP in their own stages and are documented (and
referenced, not re-derived) below and in `CLOUD_08_DEPLOYMENT_RUNBOOK.md`. A future deployment
session must re-run `terraform plan` **fresh** before any apply regardless (GCP state may drift
between now and then) — reusing these numbers as a substitute for a fresh plan would violate the
predeploy checklist in the runbook.

## Plan shapes on record (from each stage's own real plan, summarized here for freeze purposes)

| Stage | Plan | Add | Change | Destroy |
|---|---|---|---|---|
| CLOUD-03 (`platform/identity`) | real, applied | 7 | 0 | 0 |
| CLOUD-04 (`environments/dev`, Cloud Run) | real, applied (+ 2 authorized follow-ups) | 5 (+2, +0/1 change) | 0 (+0, +1) | 0 |
| CLOUD-05 (`environments/dev`, `enable_cloud_sql=true`) | real, **not applied** | 7 | 1 | 0 |
| CLOUD-06 (`environments/demo`, first deploy) | real, **not applied** | 5 | 0 | 0 |
| CLOUD-07 (`enable_monitoring=true`) | real, **not applied** | 4 | 0 | 0 |

No destroy/replace appeared in the audited CLOUD-03 through CLOUD-07 plans.

## Final cloud status matrix

| Stage | Design | Code | Plan | Deploy | Validation | Cost | Status |
|---|---|---|---|---|---|---|---|
| CLOUD-00 | ✅ | N/A (docs) | N/A | N/A | N/A | $0 | **READY** |
| CLOUD-01 | ✅ | ✅ | ✅ (reasoned, ADC-blocked at the time) | N/A | `validate` only | $0 | **READY** |
| CLOUD-02 | ✅ | ✅ | N/A (CI, not Terraform) | N/A (CI workflows) | Local test suites green | $0 | **READY** |
| CLOUD-03 | ✅ | ✅ | ✅ real, 7/0/0 | ✅ **DEPLOYED** | Live `gcloud` verification (pool ACTIVE, provider ACTIVE, bindings exact) | $0 | **READY** |
| CLOUD-04 | ✅ | ✅ | ✅ real, 5/0/0 +2 follow-ups | ✅ **DEPLOYED** | Live smoke (health + browser + CORS) | ~$0 (scale-to-zero) | **READY** |
| CLOUD-05 | ✅ | ✅ | ✅ real, 7/1/0 | ❌ not applied | Plan-only validation | $0 today, ~$9.37/mo if applied | **READY_FOR_DEPLOYMENT** |
| CLOUD-06 | ✅ | ✅ | ✅ real, 5/0/0 | ❌ not applied (DEMO doesn't exist) | Plan-only + script dry-runs | $0 today | **READY_FOR_DEPLOYMENT** |
| CLOUD-07 | ✅ | ✅ | ✅ real, 4/0/0 | ❌ not applied | Plan-only + script dry-runs | $0 today, ~$2.63/mo per env if applied | **READY_FOR_DEPLOYMENT** |
| CLOUD-08 | ✅ (this document set) | N/A (docs only) | N/A | N/A | `fmt`/`validate` clean | $0 | **READY_FOR_FREEZE** |

No stage is marked `DEPLOYED` unless independently verified live via read-only `gcloud` (CLOUD-03,
CLOUD-04) — CLOUD-05/06/07 are explicitly `READY_FOR_DEPLOYMENT`, never claimed as deployed.

## Files created this stage

`CLOUD_08_FINAL_E2E_PLAN.md`, `CLOUD_08_DEPLOYMENT_RUNBOOK.md`, `CLOUD_08_COST_ENVELOPE.md`,
`CLOUD_08_SECURITY_FREEZE.md`, `CLOUD_08_FREEZE_MANIFEST.md`, `CLOUD_DEPLOYMENT_HANDOFF.md`, this
file, plus an update to `CLOUD_ROADMAP.md`. No `.tf` file was modified — this stage is
documentation-only, matching its PLAN-ONLY mandate exactly.

## Git status

Branch `cloud/cloud-08-final-freeze`, created from CLOUD-07's exact pushed HEAD
(`1da60ca2ba7a52352c4bf71aaaaa32e075141c4e`). Working tree carries only the new `docs/cloud/CLOUD_08_*.md`
and `CLOUD_DEPLOYMENT_HANDOFF.md` files plus the `CLOUD_ROADMAP.md` update — `git diff --check`
clean, no `.tf`/workflow/script file touched. Left `READY_TO_COMMIT`, nothing committed, nothing
pushed, per explicit instruction ("No commit automático").
