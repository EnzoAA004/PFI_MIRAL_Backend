# CLOUD-03 — Change Plan (real plan obtained, still pre-authorization)

**A real `terraform plan` against live GCP was obtained this revision. Nothing was applied.** ADC
was configured (interactively, with explicit authorization) specifically so this plan could be
real rather than reasoned-from-the-files — the full transcript is saved verbatim at
`docs/cloud/CLOUD_03_TERRAFORM_PLAN_OUTPUT.txt` (textual only; no `.tfplan` binary was created or
committed).

## Terraform validation (re-run this revision)

```
$ terraform fmt -recursive .           # normalized 1 file (modules/workload-identity/main.tf), no logic change
$ cd platform/identity && terraform init && terraform validate
Success! The configuration is valid.
$ cd environments/dev && terraform init && terraform validate
Success! The configuration is valid.
$ cd environments/demo && terraform init && terraform validate
Success! The configuration is valid.
```

## Real `terraform plan` — `platform/identity` (the actual CLOUD-03 scope)

Run **without** `-refresh=false` (default refresh behavior, per instruction — Terraform did
contrast against live state, evidenced by the live data read below):

```
module.artifact_registry.data.google_artifact_registry_repository.existing[0]: Reading...
module.artifact_registry.data.google_artifact_registry_repository.existing[0]: Read complete after 1s
  [id=projects/pfi-asplanatti-fabrello-v1/locations/us-central1/repositories/pfi-containers]

Terraform will perform the following actions:

  # module.artifact_registry.google_artifact_registry_repository_iam_member.writers["serviceAccount:pfi-github-artifact-pusher@pfi-asplanatti-fabrello-v1.iam.gserviceaccount.com"] will be created
  # module.workload_identity.google_iam_workload_identity_pool.github will be created
  # module.workload_identity.google_iam_workload_identity_pool_provider.github will be created
  # module.workload_identity.google_service_account.artifact_pusher will be created
  # module.workload_identity.google_service_account_iam_member.wif_impersonation["EnzoAA004/PFI_MVPTest_Enzo_AImodule"] will be created
  # module.workload_identity.google_service_account_iam_member.wif_impersonation["EnzoAA004/PFI_MVPTest_Enzo_Backend"] will be created
  # module.workload_identity.google_service_account_iam_member.wif_impersonation["EnzoAA004/PFI_MVPTest_Enzo_Frontend"] will be created

Plan: 7 to add, 0 to change, 0 to destroy.
```

(Full resource-attribute detail — every field, not just the summary line — is in
`CLOUD_03_TERRAFORM_PLAN_OUTPUT.txt`.)

### Exact resources to add (7, matching the strict criteria exactly)

1. Workload Identity Pool — `github-pool`
2. Workload Identity Provider — `github-provider` (attribute mapping + condition as designed)
3. Service account — `pfi-github-artifact-pusher`
4. `roles/iam.workloadIdentityUser` binding — `EnzoAA004/PFI_MVPTest_Enzo_Backend`
5. `roles/iam.workloadIdentityUser` binding — `EnzoAA004/PFI_MVPTest_Enzo_Frontend`
6. `roles/iam.workloadIdentityUser` binding — `EnzoAA004/PFI_MVPTest_Enzo_AImodule`
7. `roles/artifactregistry.writer` binding, repository-scoped to `pfi-containers`

**ADD = 7. CHANGE = 0. DESTROY = 0. No REPLACE anywhere.** This matches the strict criteria exactly:
no Cloud Run, no bucket, no Cloud SQL, no GPU, no new Artifact Registry repository, no secret, no
monitoring, no scanning.

### `pfi-containers` — confirmed EXISTING, not created/replaced

The plan's only live read (`data.google_artifact_registry_repository.existing[0]`) resolved
`pfi-containers` from real GCP state — it appears nowhere in the "will be created" list. **Confirmed
not created, not replaced.**

### Existing Cloud Run / bucket / service accounts — confirmed untouched

Neither `pfi-ai-module` (Cloud Run) nor `pfi-rm-lumbar-artifacts-2026-ef` (bucket) nor any of the
four pre-existing service accounts (`pfi-training-vm`, `pfi-ai-runtime`, `pfi-backend-runtime`,
default Compute SA) appear anywhere in this plan — `platform/identity` doesn't reference any of
them. Confirmed untouched.

## `environments/dev` and `environments/demo` — confirmed no WIF duplication

Ran `terraform plan` on `environments/dev` (with placeholder image tag values, since those
variables are required but irrelevant to this check) and grepped the output for
`workload_identity`/`artifact_pusher`/`wif`: **zero matches**. `dev`'s plan shows `7 to add, 0
change, 0 destroy` of its own — but those 7 are the CLOUD-01 leftovers (Frontend runtime SA, the
`cloudsql.client` binding, 3 Cloud Run services, 2 Cloud Run IAM members) — **unrelated to CLOUD-03,
not part of this authorization request, and not applied either.** `demo` was `validate`-checked
(not planned, to avoid noise from the same CLOUD-01 leftovers) and confirmed to declare zero WIF
resources by inspection of its `.tf` files.

## API audit table (the core ask of this revision)

| API | Currently enabled? | Necessary for what resource/operation? | Necessary for PLAN? | Necessary for APPLY? | Direct cost? | Authorize next phase? |
|---|---|---|---|---|---|---|
| `iam.googleapis.com` | **No** | Creating the service account, WIF pool, WIF provider, and the `google_service_account_iam_member` impersonation bindings | **No** — empirically confirmed: the real plan above succeeded with this API disabled, because planning a *new* resource (not yet in state) never calls the target API, only `data` reads and state refreshes do | **Yes** — the four `iam.googleapis.com`-family resources' Create/SetIamPolicy calls will fail without it | No — IAM has no per-API-enablement charge | **Yes, needs authorization for APPLY** (not needed for PLAN, already obtained) |
| `iamcredentials.googleapis.com` | **No** | Not called by Terraform at all in this design. Used later, at runtime, when `google-github-actions/auth` exchanges the GitHub OIDC token for a short-lived GCP token (the `generateAccessToken` RPC) | No | **No, for the `terraform apply` itself** — but **yes, for the WIF mechanism to actually work** once a workflow tries to authenticate | No | **Yes, needs authorization** — required before any publish workflow can succeed, even though `apply` itself doesn't touch it |
| `sts.googleapis.com` | **No** | Also runtime-only: the STS token-exchange step inside `google-github-actions/auth`'s OIDC→GCP flow | No | No, for `apply` | No | **Yes, needs authorization** — same reasoning as `iamcredentials.googleapis.com` |
| `cloudresourcemanager.googleapis.com` | **No** | Project-level operations in the general case (e.g. `google_project_iam_member`, `google_project` data source) — **neither is used anywhere in the `platform/identity` module** | **No** — confirmed empirically, the real plan succeeded without it | **Likely no, for this specific resource set** — none of the 7 planned resources are project-level IAM or project data lookups; reasoned, not yet proven by a real `apply` (not authorized) | No | **Not requested this phase** — flagged as "probably not needed for CLOUD-03's exact scope," to be confirmed for real only if `apply` unexpectedly asks for it |
| `containerscanning.googleapis.com` | **No** | Automatic vulnerability scanning of pushed images | No | No — this stage's resource set never references it | **Yes if enabled** (per-image scan cost) | **No — stays disabled.** Not part of CLOUD-03, per explicit instruction. |
| `artifactregistry.googleapis.com` | **Yes** (already) | The repository itself, and the repository-scoped IAM binding | Yes — this is exactly what the plan's live data read used | Yes | No (storage-based billing only) | N/A — already enabled, predates this project |

### `cloudresourcemanager.googleapis.com` — the specific justification asked for

Not part of this authorization request. The empirical plan result proves it isn't needed for
*this* resource set to `plan`. Whether it's needed for `apply` is a reasoned "probably not" (none
of the 7 resources are project-IAM-policy or project-metadata resources), not yet proven by an
actual `apply` since none was authorized. If a future authorized `apply` attempt does fail citing
this API, that would be the first real evidence it's needed — not something to enable pre-emptively
"just in case."

## Cost check, post-real-plan

See `CLOUD_03_COST_CHECK.md` for the full INFRASTRUCTURE vs. ARTIFACT STORAGE split. Summary:
all 7 real-plan resources cost **$0** (WIF/IAM/SA carry no charge); the only cost category is image
storage, entirely usage-based and not yet incurred (no image has been pushed).

## Rollback strategy (unchanged in substance)

`terraform state`/`terraform show` as source of truth if `apply` ever partially completes; no
improvised manual `gcloud` fixes; scoped `terraform destroy` on the same 7 resources fully reverses
this stage without touching `pfi-containers` or any pre-existing resource (none of them were ever
Terraform-managed by this module).

## Docs updated this revision

`CLOUD_03_ARTIFACT_REGISTRY_WIF.md`, `CLOUD_03_SECURITY_REVIEW.md`, `CLOUD_03_COST_CHECK.md`, this
file, plus the new `CLOUD_03_TERRAFORM_PLAN_OUTPUT.txt` (raw plan transcript).

## Git status (this worktree)

Branch `cloud/cloud-03-artifact-wif-backend`. Still fully uncommitted (`READY_TO_COMMIT`) — no
commit was authorized yet for this stage, consistent with every prior stage's pattern (design/plan
first, git closure only after a separate explicit instruction).
