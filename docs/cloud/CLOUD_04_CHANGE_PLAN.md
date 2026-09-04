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
