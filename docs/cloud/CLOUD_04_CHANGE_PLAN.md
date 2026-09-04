# CLOUD-04 — Change Plan (real plan obtained, still pre-authorization)

**A real `terraform plan` against live GCP was obtained this revision, using the operator's own
already-authenticated `gcloud` access token (no ADC interactive login needed, no service-account
key). Nothing was applied.** Full transcript: `CLOUD_04_TERRAFORM_PLAN_OUTPUT.txt`.

## Pre-plan read-only audit (this revision)

```
$ gcloud config get-value project                          → pfi-asplanatti-fabrello-v1
$ gcloud billing projects describe ...                      → billingEnabled: true
$ gcloud services list --enabled                             → run.googleapis.com, artifactregistry.googleapis.com,
                                                                 iam.googleapis.com, sts.googleapis.com present;
                                                                 sqladmin.googleapis.com / vpcaccess.googleapis.com NOT enabled
$ gcloud run services list                                   → only pfi-ai-module (confirmed sole pre-existing service)
$ gcloud sql instances list                                  → API disabled — confirms zero Cloud SQL instances exist
$ gcloud iam service-accounts list                           → pfi-training-vm, pfi-ai-runtime, pfi-backend-runtime,
                                                                 pfi-github-artifact-pusher, default compute SA
$ gcloud projects get-iam-policy                              → pfi-backend-runtime has roles/run.invoker only (project-level);
                                                                 pfi-ai-runtime has roles/storage.viewer only; no cloudsql.client
                                                                 anywhere yet
$ gcloud iam workload-identity-pools providers describe       → attributeCondition already allowlists all 3 repos by full
  github-provider                                              name, no ref/branch restriction — no WIF trust change needed
$ gcloud iam service-accounts get-iam-policy                  → workloadIdentityUser already bound for all 3 repos
  pfi-github-artifact-pusher
$ gcloud artifacts repositories get-iam-policy pfi-containers → pfi-github-artifact-pusher has artifactregistry.writer,
                                                                 unchanged since CLOUD-03
$ gcloud compute networks list                                → only the default auto-mode VPC; no VPC connector,
                                                                 not needed (no private DB to reach yet)
$ gcloud storage buckets list                                 → only pfi-rm-lumbar-artifacts-2026-ef (pre-existing, untouched)
$ gcloud run services list (Cloud Run quotas)                  → default quotas, far above 2 tiny DEV services' needs
```

**Confirmed: `pfi-ai-module` is still the only pre-existing Cloud Run service. Not touched by any
change below.**

## Terraform validation (this revision)

```
$ terraform init       # Success — modules initialized, google provider v8.1.0
$ terraform validate   # Success! The configuration is valid.
```

## Real `terraform plan` (backend + frontend at their exact CLOUD-03 SHAs)

```
$ terraform plan \
    -var="backend_image=us-central1-docker.pkg.dev/pfi-asplanatti-fabrello-v1/pfi-containers/backend:dee0826c9e1121e348192058acde841b6f933608" \
    -var="frontend_image=us-central1-docker.pkg.dev/pfi-asplanatti-fabrello-v1/pfi-containers/frontend:1c77231e2e1833bada6dc1c0f4f622f1d32e3470" \
    -var="ai_image=us-central1-docker.pkg.dev/pfi-asplanatti-fabrello-v1/pfi-containers/pfi-ai-module:unused"

Plan: 5 to add, 0 to change, 0 to destroy.
```

`ai_image` was passed a placeholder value only because the variable has no default — it is never
used: `module.ai_cloud_run` is not instantiated (`enable_ai_service=false`), confirmed by its
absence from the 5 planned resources.

### Resources to add

1. `module.backend_cloud_run.google_cloud_run_v2_service.this` — `pfi-backend-dev`, image pinned to
   the exact `backend:dee0826...` digest-resolving tag, `min_instance_count=0`, `startup_probe` on
   `/api/system/health`.
2. `module.backend_cloud_run.google_cloud_run_v2_service_iam_member.public[0]` — `allUsers` →
   `roles/run.invoker` on `pfi-backend-dev`.
3. `module.frontend_cloud_run.google_cloud_run_v2_service.this` — `pfi-frontend-dev`, image pinned
   to `frontend:1c77231...`, `port=80`, `startup_probe` on `/`.
4. `module.frontend_cloud_run.google_cloud_run_v2_service_iam_member.public[0]` — same public
   invoker pattern.
5. `module.iam.google_service_account.created["frontend_runtime"]` — `pfi-frontend-runtime-dev`,
   zero IAM role grants.

### Hard gate — checked against every condition

| Condition | Result |
|---|---|
| `destroy > 0` | **0** |
| change to a preexisting resource | **0** — `pfi-ai-module`, `pfi-containers`, the WIF pool/provider/bindings, and the storage bucket do not appear in the plan at all |
| replace | **0** |
| touch to `pfi-ai-module` | none |
| touch to `pfi-containers` | none (only read as a data source for `repository_url`, same as CLOUD-01/03) |
| touch to the bucket | none |

**All clear. No condition triggers a STOP.**

## Rollback procedure (documented now, executed only after first successful apply + stable revision)

1. `gcloud run revisions list --service=pfi-backend-dev --region=us-central1` (or `pfi-frontend-dev`)
   to identify revision N (the one just deployed) and confirm it is `Ready`/serving 100% traffic.
2. Deploy a second revision N+1 — in practice, the next authorized `terraform apply` with a changed
   image tag or env var (never an artificially broken build).
3. `gcloud run services update-traffic pfi-backend-dev --region=us-central1 --to-revisions=N=100`
   (or the Terraform equivalent: re-apply pinned to revision N's image) to shift 100% of traffic
   back to N.
4. Record: time to detect the need for rollback (n/a for a controlled test), time to execute step 3,
   and confirmation that health/smoke checks pass again on N.

Not executed this revision (no revision exists yet to roll back from/to) — see
`CLOUD_04_CLOUD_RUN_DEV.md` "Rollback strategy" and this file's future update once applied.

## What happens on `terraform apply` (once authorized)

Exactly the 5 resources above, nothing else. `terraform apply "cloud04.tfplan"` (the saved plan
file, not a fresh plan) so the applied set is byte-identical to what was reviewed here.

## Apply result (executed, `terraform apply cloud04.tfplan`)

**3 of 5 resources created successfully. 1 blocked by a GCP API validation rule not caught by
`terraform plan`/`validate` (plan-time validation doesn't call the live API's admission checks).
1 never attempted (its own dependency failed first). No change/destroy/replace was attempted or
occurred — this is a partial-create, not a scope deviation.**

| # | Resource | Result |
|---|---|---|
| 1 | `module.iam.google_service_account.created["frontend_runtime"]` | ✅ created |
| 2 | `module.backend_cloud_run.google_cloud_run_v2_service.this` | ✅ created, `Ready` |
| 3 | `module.backend_cloud_run.google_cloud_run_v2_service_iam_member.public[0]` | ✅ created |
| 4 | `module.frontend_cloud_run.google_cloud_run_v2_service.this` | ❌ **failed** |
| 5 | `module.frontend_cloud_run.google_cloud_run_v2_service_iam_member.public[0]` | ⏭️ not attempted (depends on #4) |

### Root cause (confirmed, not guessed)

```
Error 400: template.containers.resources.limits.memory: Invalid value specified for memory.
Total memory < 512 Mi is not supported with cpu always allocated (unthrottled).
```

Confirmed against the successfully-created Backend service's own live config
(`gcloud run services describe pfi-backend-dev`): `run.googleapis.com/cpu-throttling: "false"` is
the GCP-side default when the `cloud-run-service` module doesn't set `resources.cpu_idle` at all
(neither Backend nor Frontend's module call did). Backend's `memory=512Mi` happens to satisfy the
512Mi floor this default implies; Frontend's `memory=256Mi` does not. `terraform plan`/`validate`
cannot catch this — it is a live API admission-control rule, not a schema-level constraint.

### Not fixed without authorization

Two equally valid fixes exist (bump Frontend `memory` to `512Mi` to match Backend, or add
`cpu_idle = true` to let CPU throttle to zero while idle — the latter is arguably the more correct
choice for a scale-to-zero DEV service and keeps the reviewed 256Mi footprint). Neither was applied
without checking in first: bumping memory or adding an unreviewed attribute is a change to the
already-approved plan, which the CLOUD-04 apply authorization explicitly excluded ("No recalcular
ni ampliar el alcance durante el apply"). Reported for a fresh, explicit authorization instead.

### Current live Terraform state

```
$ terraform state list
data.google_project.current
module.artifact_registry.data.google_artifact_registry_repository.existing[0]
module.backend_cloud_run.google_cloud_run_v2_service.this
module.backend_cloud_run.google_cloud_run_v2_service_iam_member.public[0]
module.iam.google_service_account.created["frontend_runtime"]
module.storage.data.google_storage_bucket.existing[0]
```

No orphaned/half-created Frontend resource exists in GCP — the API rejected the create request
outright before any object was registered, confirmed via `gcloud run services list` (only
`pfi-ai-module` and `pfi-backend-dev` exist).

## Follow-up apply 1 — `cpu_idle` fix (authorized separately, option B)

Added a proper `cpu_idle` variable to the `cloud-run-service` module (default `false`, Backend
unaffected) and set `cpu_idle = true` for Frontend only. A fresh `terraform plan` (not reusing the
stale `cloud04.tfplan`) then showed an *unrelated* drift: Backend's `startup-cpu-boost` annotation,
never actually present on the live resource despite the module's old unconditional default. Per
explicit instruction, this was isolated declaratively (made `startup_cpu_boost` nullable, `null` =
omit the annotation, set explicitly to `null` for Backend) rather than masked with a blanket
`ignore_changes`. Result: **`Plan: 2 to add, 0 to change, 0 to destroy`** — applied.
`Apply complete! Resources: 2 added, 0 changed, 0 destroyed.`

## Follow-up apply 2 — CORS real-URL fix (authorized separately)

Browser smoke surfaced a real CORS preflight rejection: Backend's `PFI_CORS_ALLOWED_ORIGINS` held a
*predicted* Frontend URL format that turned out not to match what `google_cloud_run_v2_service.uri`
(and the browser's `Origin` header) actually use. Fixed by replacing the prediction with
`data "google_cloud_run_v2_service" "frontend_lookup"` (a name-based data source read of the
already-created Frontend service — genuinely "the real URL", without a circular module dependency)
and removing the now-confirmed-unused predicted entry. Plan: **`0 to add, 1 to change, 0 to
destroy`** — the one change was exactly the `PFI_CORS_ALLOWED_ORIGINS` env var value on
`pfi-backend-dev`, confirmed via the plan diff that every other attribute (image, SA, CPU, memory,
scale, ingress) was in the "unchanged attributes hidden" set. Applied:
`Apply complete! Resources: 0 added, 1 changed, 0 destroyed.`

## Final state

Both `pfi-backend-dev` and `pfi-frontend-dev` live, smoke-tested (including a real browser smoke
test), zero unintended resources, `pfi-ai-module`/Cloud SQL/Artifact Registry/WIF/bucket/IAM
untouched throughout every step above.
