# PFI Terraform — CLOUD-01 (foundation, plan-only)

Converts the CLOUD-00 design into real, validatable Terraform. **No resource in Google Cloud has
been created, modified, or destroyed by this stage — `terraform apply` was never run.**

## Layout

```
infra/terraform/
  versions.tf, providers.tf   # reference copies — see their headers; the real,
                               # runnable definitions live inside each environment
  modules/
    artifact-registry/  # EXISTING_EXTERNAL by default (pfi-containers)
    cloud-run-service/  # reusable — Frontend, Backend, AI all use this one module
    iam/                 # service accounts, project role bindings, WIF (design)
    storage/             # EXISTING_EXTERNAL by default (pfi-rm-lumbar-artifacts-2026-ef)
    secrets/             # Secret Manager containers, disabled by default
    cloud-sql/           # disabled by default (enabled = false everywhere)
    monitoring/          # disabled by default (enabled = false everywhere)
  environments/
    dev/                 # runnable root module
    demo/                # runnable root module
```

`environments/dev` and `environments/demo` are the only two directories you actually run
`terraform` in — `modules/` has no standalone root module, and the top-level `versions.tf`/
`providers.tf` are a documented reference kept in sync with the (duplicated) blocks inside each
environment.

## Prerequisites

- Terraform v1.15.8+ (installed via `winget install Hashicorp.Terraform` this stage).
- `gcloud auth application-default login` for local `plan` runs. No service-account JSON key is
  ever used, locally or in CI (CI uses Workload Identity Federation — see `modules/iam`).
- Copy `terraform.tfvars.example` to `terraform.tfvars` in the environment you're planning, and
  fill in real image tags. Never commit `terraform.tfvars`.

## Running a plan (never `apply`)

```bash
cd infra/terraform/environments/dev
terraform init
terraform fmt -check -recursive ..
terraform validate
terraform plan
```

## Existing-resource strategy

Three real resources already exist in `pfi-asplanatti-fabrello-v1` (found by the CLOUD-00 audit,
verified again this stage via read-only `gcloud` commands): the `pfi-containers` Artifact
Registry repo, the `pfi-rm-lumbar-artifacts-2026-ef` Storage bucket, and the `pfi-backend-runtime`/
`pfi-ai-runtime` service accounts. All three are modeled as **EXISTING_EXTERNAL**: referenced via
Terraform `data` sources or plain variables, never `import`ed and never proposed for re-creation.
See each module's `main.tf` header comment for the exact import command that would be used if a
future stage deliberately decides to bring one of them under Terraform management.

The `pfi-ai-module` Cloud Run service found in the audit is **not referenced by this Terraform
project at all** — its purpose was never verified, so CLOUD-01 neither manages it nor assumes it
should be replaced by `pfi-ai-service-dev`/`-demo`.

## Feature flags (all default to the safe/off state)

| Flag | Default | Why |
|---|---|---|
| `enable_cloud_sql` | `false` | Cloud SQL is the one always-on cost in the stack (`CLOUD_COST_MODEL.md`); `sqladmin.googleapis.com` isn't even enabled on the project yet |
| `enable_monitoring` | `false` | No alert policy until CLOUD-07 |
| `secrets.enabled` | `false` | `secretmanager.googleapis.com` isn't enabled; no real secret value is ever declared here regardless |
| `iam.enable_wif` | `true` in both environments | Explicitly whitelisted by the CLOUD-01 instruction as safe to preview in a plan (no cost, never applied) |

## Region

`us-central1` for every resource — matches where the existing Artifact Registry, Cloud Run
service, and Storage bucket already live, minimizing cross-region latency and egress. No existing
resource is moved.
