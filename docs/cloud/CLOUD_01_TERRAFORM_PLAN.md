# CLOUD-01 — Terraform Plan

## Status: `PLAN_BLOCKED_BY_MISSING_LOCAL_CREDENTIALS`

`terraform plan` requires live read access to GCP (to refresh state and evaluate the `data`
sources for the existing Artifact Registry repo and Storage bucket), which needs Application
Default Credentials. This environment has none configured, and completing
`gcloud auth application-default login` requires an interactive browser sign-in this session
cannot perform non-interactively. The user was asked and chose to document this as a blocker
rather than complete the login themselves in this pass.

**This is not `PLAN_BLOCKED_BY_DISABLED_API`** — no GCP API rejected the request; the request was
never made because no local credential exists to make it with.

## What was verified instead

`terraform validate` (no live credentials needed — it only checks syntax and internal schema
consistency, no API calls) passed cleanly, first attempt, for both environments:

```
$ terraform validate   # environments/dev
Success! The configuration is valid.

$ terraform validate   # environments/demo
Success! The configuration is valid.
```

`terraform init` also succeeded for both (resolves and downloads the `hashicorp/google` provider,
writes `.terraform.lock.hcl` — this step does not require GCP credentials, only network access to
the Terraform Registry).

## Expected shape of a real plan, reasoned from the configuration (not fabricated numbers)

This section states what the configuration *would* propose, based on reading the `.tf` files
themselves — not a claim that a plan was executed. Once ADC is configured, running
`terraform plan` in `environments/dev` (after copying `terraform.tfvars.example` to
`terraform.tfvars` with real image tags) should show, resource by resource:

| Resource | Expected action | Why |
|---|---|---|
| `data.google_artifact_registry_repository.existing["artifact_registry"]` | read (not a plan action) | `create_repository=false` |
| `data.google_storage_bucket.existing["storage"]` | read (not a plan action) | `create_bucket=false` |
| `google_service_account.created["frontend_runtime"]` | **+ create** | new SA, doesn't exist yet |
| `google_service_account.created["ci_deploy"]` | **+ create** | new SA, doesn't exist yet |
| `google_project_iam_member.bindings[...]` (4 entries) | **+ create** | additive role grants, none exist yet as Terraform-managed resources |
| `google_iam_workload_identity_pool.github[0]` | **+ create** | `enable_wif=true` in dev |
| `google_iam_workload_identity_pool_provider.github[0]` | **+ create** | same |
| `google_service_account_iam_member.wif_impersonation[0]` | **+ create** | same |
| `google_secret_manager_secret.this[*]` | none proposed | `secrets.enabled=false` |
| `google_sql_database_instance.this[0]` | none proposed | `enable_cloud_sql=false` |
| `google_monitoring_alert_policy.high_5xx[0]` | none proposed | `enable_monitoring=false` |
| `google_cloud_run_v2_service.this["backend_cloud_run"]` | **+ create** | `pfi-backend-dev` doesn't exist |
| `google_cloud_run_v2_service.this["frontend_cloud_run"]` | **+ create** | `pfi-frontend-dev` doesn't exist |
| `google_cloud_run_v2_service.this["ai_cloud_run"]` | **+ create** | `pfi-ai-service-dev` doesn't exist (distinct from the pre-existing, unreferenced `pfi-ai-module`) |
| `google_cloud_run_v2_service_iam_member.public[*]` | **+ create** (×2, Backend+Frontend) | `allow_unauthenticated=true` on those two only |

Expected summary: **some number of `+ create`, 0 to change, 0 to destroy.** Nothing in this
configuration modifies or removes any existing resource — every resource declared either targets a
brand-new name (`pfi-*-dev`, never colliding with `pfi-ai-module`) or is a `data` source (no
mutation). **If a real `terraform plan` ever shows `destroy > 0` or proposes replacing
`pfi-containers`, `pfi-rm-lumbar-artifacts-2026-ef`, or any of the four pre-existing service
accounts, that is a STOP condition** per the CLOUD-01 instruction — investigate before proceeding,
do not `apply`.

## Demo plan (conceptual comparison)

Same resource set, same "some `+ create`, 0 change, 0 destroy" shape, differing only in:
`min_instance_count=1` (not `0`) on Backend and Frontend, and a larger (still uncreated) Cloud SQL
tier documented (`db-custom-1-3840` vs. `db-f1-micro`) — both still gated off by
`enable_cloud_sql=false`.

## Next step to unblock

Run, in an interactive terminal:
```bash
gcloud auth application-default login
```
then, from `infra/terraform/environments/dev`:
```bash
cp terraform.tfvars.example terraform.tfvars   # fill in real image tags
terraform plan
```
Paste or attach the real output to this file (or a follow-up doc) before treating the plan section
of CLOUD-01 as fully closed — this document intentionally stops short of inventing numbers it
cannot back with a real run.
