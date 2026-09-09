# CLOUD-03 — Artifact Registry + Workload Identity Federation

**Design finalized, Terraform validated, a real `terraform plan` obtained. Still nothing applied —
no GCP resource created, modified, or deleted.** This revision replaces the per-environment WIF
duplication from the first draft with a single shared platform identity, per explicit correction.

## What changed since the first draft

The first draft declared `github-pool-dev`/`github-pool-demo` (two pools) inside
`environments/dev` and `environments/demo` respectively. That was wrong: WIF is the trust
relationship between GitHub and GCP, a project-wide concept, not an application environment.
Corrected to a single shared identity, owned by a new root module:

```
infra/terraform/
  modules/
    workload-identity/   # NEW — pool + provider + SA + impersonation bindings, reusable
  platform/
    identity/             # NEW — the ONLY place workload-identity is instantiated
  environments/
    dev/    # no longer creates or references its own WIF pool/provider/SA
    demo/   # same
```

`environments/dev` and `environments/demo` now contain **zero** WIF-related resources — confirmed
by grepping their real `terraform plan` output for `workload_identity`/`artifact_pusher`/`wif`:
zero matches in either. `modules/iam` was reverted to its pre-CLOUD-03 shape (runtime service
accounts + project-level role grants only); the WIF-specific resources that had been added to it
were moved wholesale into `modules/workload-identity`.

## ADC now configured (this stage)

Per explicit authorization, ran interactively:

```
$ gcloud auth application-default login
Credentials saved to file: [...\gcloud\application_default_credentials.json]
Quota project "pfi-asplanatti-fabrello-v1" was added to ADC
```

Verified without printing the token:

```
$ gcloud auth application-default print-access-token > /dev/null && echo OK
OK
$ gcloud config get-value project
pfi-asplanatti-fabrello-v1
```

Correct project confirmed. No credential file was committed to Git (it lives outside any repo, in
the user's gcloud config directory).

## Final WIF architecture

```
GitHub Actions (workflow_dispatch only, any of the 3 repos)
  → GitHub OIDC token (id-token: write)
  → Workload Identity Pool "github-pool"                    (ONE, shared)
  → Workload Identity Provider "github-provider"             (ONE, shared)
  → impersonates: pfi-github-artifact-pusher@...             (ONE, shared)
  → roles/artifactregistry.writer on pfi-containers only
```

| Count | Resource |
|---|---|
| 1 | Workload Identity Pool (`github-pool`) |
| 1 | Workload Identity Provider (`github-provider`) |
| 1 | Service account (`pfi-github-artifact-pusher`) |
| 3 | `roles/iam.workloadIdentityUser` bindings, one `principalSet` per allowlisted repo |
| 1 | `roles/artifactregistry.writer` binding, repository-scoped to `pfi-containers` |

**Attribute mapping:**
```hcl
attribute_mapping = {
  "google.subject"       = "assertion.sub"
  "attribute.repository" = "assertion.repository"
  "attribute.ref"        = "assertion.ref"
  "attribute.actor"      = "assertion.actor"
}
```

**Attribute condition** (explicit allowlist, verified in the real plan output below):
```
assertion.repository in ["EnzoAA004/PFI_MVPTest_Enzo_Backend","EnzoAA004/PFI_MVPTest_Enzo_Frontend","EnzoAA004/PFI_MVPTest_Enzo_AImodule"]
```

`workflow_dispatch` remains the only trigger on all three publish workflows — no fork PR can ever
reach this path.

## Real `terraform plan` (platform/identity) — see CLOUD_03_CHANGE_PLAN.md for the full transcript

```
module.artifact_registry.data.google_artifact_registry_repository.existing[0]: Reading...
module.artifact_registry.data.google_artifact_registry_repository.existing[0]: Read complete after 1s
  [id=projects/pfi-asplanatti-fabrello-v1/locations/us-central1/repositories/pfi-containers]
...
Plan: 7 to add, 0 to change, 0 to destroy.
```

`pfi-containers` resolved as a **live data read** (real API call, not a stub) — confirmed
`EXISTING`, never proposed for create/replace. Exactly 7 resources to add: the pool, the provider,
the service account, 3 impersonation bindings, 1 repository IAM binding. No Cloud Run, no bucket,
no Cloud SQL, no secret, no monitoring policy, no scanning — none of those appear anywhere in this
plan.

## Status

Design, Terraform, and workflows complete, validated, and now backed by a real `terraform plan`
against live GCP (read-only — the plan itself made no write). See `CLOUD_03_CHANGE_PLAN.md` for the
full plan transcript and the exact commands a future authorized rollout would run.
