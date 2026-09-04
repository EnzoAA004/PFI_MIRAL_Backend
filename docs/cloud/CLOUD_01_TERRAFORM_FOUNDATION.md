# CLOUD-01 — Terraform Foundation (plan-only)

**No GCP resource was created, modified, or deleted by this stage. `terraform apply` was never
run.** This document records what was built and validated, and why each design decision was made.

## Tooling installed this stage

Terraform was not previously installed. Installed via `winget install --id Hashicorp.Terraform`
(official HashiCorp package, local-only, no network resource created) with explicit user
authorization before running. Result: **Terraform v1.15.8** (latest at install time: v1.16.1 — not
required, no functionality used here needs it).

## Git base

CLOUD-00's documentation was still uncommitted at the start of this stage. Per this stage's
explicit authorization ("Si para crear una rama limpia es necesario cerrar Git de CLOUD-00: está
autorizado hacer commit... push... SOLO documentación CLOUD-00"):

- Committed (user identity): `782862aad65ca56c656e447ed848e6c9762cb905` —
  `docs: add cloud architecture and cost audit` — 8 files, `docs/cloud/` only.
- Pushed to `origin/cloud/cloud-00-architecture-audit`.
- `cloud/cloud-01-terraform-foundation` branched from that exact commit.

## Re-verified GCP state (read-only, same as CLOUD-00 but re-checked live)

| Resource | Detail |
|---|---|
| Artifact Registry | `pfi-containers`, format DOCKER, **region `us-central1`** (CLOUD-00 didn't capture the region explicitly — confirmed now via `gcloud artifacts repositories describe`), 433.7 MB |
| Cloud Run | `pfi-ai-module`, `us-central1`, 4 vCPU/2Gi, `maxScale=1`, no `minScale` |
| Storage | `pfi-rm-lumbar-artifacts-2026-ef`, `US-CENTRAL1` |
| Service accounts | `pfi-backend-runtime`, `pfi-ai-runtime`, `pfi-training-vm`, default Compute SA |
| Enabled APIs (relevant) | `artifactregistry`, `compute`, `logging`, `monitoring`, `run`, `storage(-api/-component)`, `sql-component` |
| **Disabled APIs (relevant, newly confirmed this stage)** | `iam.googleapis.com`, `iamcredentials.googleapis.com`, `cloudresourcemanager.googleapis.com`, `secretmanager.googleapis.com`, `sqladmin.googleapis.com`, `sts.googleapis.com` — **none enabled** |

This last row directly shaped the module design: any Terraform `data` source that would need one
of those disabled APIs during `plan` (a live read) was avoided; resources proposing a future
*create* (never applied) don't need their API enabled to be planned, only to be applied — so IAM/
WIF/Secret Manager/Cloud SQL resources could still be written and are feature-flagged off by
default for the reasons in the table below, not because writing them was blocked.

## Existing-resource strategy: MANAGED_BY_TERRAFORM / EXISTING_EXTERNAL / TO_BE_IMPORTED_LATER

| Resource | Decision | Why |
|---|---|---|
| `pfi-containers` (Artifact Registry) | **EXISTING_EXTERNAL** | Already holds real images; no reason to risk a duplicate-create proposal. Referenced via `data` source, `create_repository=false` by default. Import command documented in the module for a deliberate future decision. |
| `pfi-rm-lumbar-artifacts-2026-ef` (Storage) | **EXISTING_EXTERNAL** | Same reasoning — already holds artifacts, referenced via `data` source, `create_bucket=false`. |
| `pfi-backend-runtime`, `pfi-ai-runtime` (service accounts) | **EXISTING_EXTERNAL** | Referenced as plain email-string variables (no `data` source — `iam.googleapis.com` isn't enabled, and a live lookup isn't needed since the emails are already known and deterministic). IAM role grants (`google_project_iam_member`) are still additive proposals in the plan. |
| `pfi-frontend-runtime`, CI/CD deploy identity | **TO_BE_CREATED** (new, not existing) | No Frontend runtime SA exists yet; least privilege means it must not reuse Backend/AI's accounts. A brand-new CI/CD identity, distinct from the four found in the audit, matches ADR-003. |
| `pfi-ai-module` (Cloud Run, pre-existing) | **NOT REFERENCED** | Its purpose was never verified in CLOUD-00 or here — this Terraform project does not manage it, does not import it, and does not assume `pfi-ai-service-dev` replaces it. A separate decision, out of scope for CLOUD-01. |

No `terraform import` was run this stage, per the explicit preference for CLOUD-01.

## Directory structure

```
infra/terraform/
  versions.tf, providers.tf          # reference copies (see file headers)
  modules/
    artifact-registry/  cloud-run-service/  iam/  storage/  secrets/  cloud-sql/  monitoring/
  environments/
    dev/   (main.tf, variables.tf, outputs.tf, terraform.tfvars.example)
    demo/  (same shape)
  README.md
```

All 7 modules and both environments were written and are wired together — nothing was skipped for
being "not needed yet"; the modules whose resources depend on an open decision (Cloud SQL, Secret
Manager real values, Monitoring alert policies, WIF pool activation) are simply **feature-flagged
off by default** rather than left unimplemented.

## Region decision

**`us-central1`** for every resource, matching where the Artifact Registry, Cloud Run service, and
Storage bucket already live (re-verified this stage). No existing resource is moved. Minimizes
cross-region latency (Backend ↔ AI service ↔ future Cloud SQL) and avoids inter-region egress
charges entirely for internal traffic.

## Terraform / provider versions

```hcl
terraform {
  required_version = ">= 1.9.0"
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 8.1"
    }
  }
}
```

Detected local Terraform: v1.15.8. Google provider resolved by `terraform init`: **v8.1.0**
(published 2026-09-01 — [registry.terraform.io/providers/hashicorp/google](https://registry.terraform.io/providers/hashicorp/google), verified via live search this stage, not
guessed). `~> 8.1` picks up patch releases automatically, requires a deliberate edit for a minor/
major bump.

## Provider authentication

No `credentials = file(...)` block anywhere. `provider "google" { project = var.project_id; region
= var.region }` only — local runs use Application Default Credentials, CI/CD uses Workload
Identity Federation (see `modules/iam` and `CLOUD_SECURITY_MODEL.md`). No service-account JSON key
exists or is referenced anywhere in this repository.

## Cloud Run module design

One reusable module (`modules/cloud-run-service`) parameterizes everything the CLOUD-01
instruction asked for: name, image, region, service account, plain + secret-sourced env vars,
min/max instances, CPU, memory, concurrency, timeout, ingress, a public/private toggle
(`allow_unauthenticated`, wired to `google_cloud_run_v2_service_iam_member`), and an optional
startup HTTP probe (`health_check_path`). The same module instantiates Frontend, Backend, and the
AI-CPU service in each environment with different inputs — no per-service duplication.

## Frontend Cloud Run blocker — resolved

CLOUD-00 found `docker/nginx.conf` hardcoding `listen 80`, incompatible with Cloud Run's injected
`$PORT`. Fixed on a dedicated branch, **`cloud/cloud-01-frontend-cloudrun-compat`**, branched from
`origin/main` (the stable baseline — the main Frontend worktree had unrelated in-progress research
changes, so branching from there would have mixed unrelated work into this fix, violating "no
mezclar sin trazabilidad").

The fix reuses the exact pattern already in place for `env.js`: `nginx.conf` now ships with a
literal placeholder (`__PFI_NGINX_PORT__` — not a real nginx variable, so it can never collide with
`$uri`/`$document_root`/etc.), and `entrypoint.sh` rewrites it in place at container start via
`sed`, defaulting to `80` when `$PORT` isn't set (preserving the exact previous local-Docker
behavior). Cloud Run's official `envsubst`-on-templates mechanism was deliberately **not** used —
it substitutes every environment variable found in the template, which would silently corrupt
`$uri`/`$document_root` in the `location` blocks (a well-known gotcha), whereas the existing
project pattern (a small shell script generating one runtime file) sidesteps that entirely.

Verified locally (Docker, no GCP needed):

| Scenario | Result |
|---|---|
| `docker run` (no `PORT` set) | nginx listens on **80** (unchanged), `curl` → HTTP 200 |
| `docker run -e PORT=8080` (Cloud Run simulation) | nginx listens on **8080**, `curl` → HTTP 200 |

`npm run typecheck`, `npm run lint`, `npm test` all pass unchanged on this branch (the fix touches
only `docker/nginx.conf` and `docker/entrypoint.sh`, no application code). Left `READY_TO_COMMIT`,
not committed/pushed per the standing instruction.

## Backend Cloud Run compatibility — confirmed, no change

Smoke-tested the existing EXT-04B5 Backend image with `docker run -e PORT=9090`: log shows
`Tomcat initialized with port 9090` — **`$PORT` is genuinely respected**, no code change needed.

`/actuator/health` and `/` both returned **HTTP 401**, not 404 — confirming and sharpening the
CLOUD-00 finding: there is no Actuator dependency in `pom.xml` (grepped, none found), and the
existing Spring Security configuration denies every unauthenticated request by default (nothing is
`permitAll()`-ed), so even after adding Actuator, `/actuator/health` would need an explicit
security-config exception before Cloud Run's health probe could reach it unauthenticated. This is
a **documented finding for a future stage**, not a change made here (backend not modified — "no
cambiar si ya funciona" applies to the parts that do work, i.e. `$PORT`).

## AI service compatibility — confirmed via the E2E mock-AI image

Smoke-tested the existing mock-AI E2E harness image with `docker run -e PORT=9500`: `/health`
returned HTTP 200, log confirms `listening on 9500`. This validates the `${PORT:-8000}` pattern the
real `ai_service/` Dockerfile already uses (per the CLOUD-00 audit) via the harness image that
exercises the same startup contract; the full real image (with real PyTorch/timm dependencies) was
not rebuilt this stage to avoid a heavy, slow build for a check the harness image already answers.

### AI model artifact strategy (checkpoint blocker — decision, not yet implemented)

CLOUD-00 found the AI Dockerfile never copies the EXT-03 subarticular checkpoint. Three options
were weighed:

| Option | Tradeoff |
|---|---|
| A. Bake into image | Simplest, but grows the image on every model update and requires a rebuild+redeploy for a model-only change |
| **B. Download from Cloud Storage at startup (chosen target)** | Model artifacts become versioned objects in GCS, decoupled from the container image lifecycle — a model update doesn't require a new image; matches "model artifacts externos/versionados, no Git" from the instruction |
| C. Mounted volume/Cloud Storage FUSE | More moving parts than needed at this scale; revisit only if artifact size or count grows significantly |

**Decision: option B**, to be implemented at the stage that actually touches the AI Dockerfile
(not this one — CLOUD-01 is Terraform-only for the AI service; the `modules/storage` module
already anticipates this by keeping the existing bucket as a referenceable `EXISTING_EXTERNAL`
resource). No bucket was created; no object was uploaded.

## IAM / Workload Identity Federation

`modules/iam` creates (in a plan, never applied): one new Frontend runtime SA and one new CI/CD
deploy SA per environment, grants existing Backend/AI runtime SAs only the specific roles they
need (`roles/cloudsql.client` for Backend; nothing extra for AI in this stage), and writes the full
WIF pool/provider/impersonation-binding design gated behind `enable_wif` (set `true` in both
environments — explicitly whitelisted by the instruction as safe to preview). No `roles/owner` or
`roles/editor` anywhere.

## Secrets, Cloud SQL, Monitoring

All three feature-flagged **off** (`secrets.enabled=false`, `enable_cloud_sql=false`,
`enable_monitoring=false`) in both environments, exactly as required. `modules/secrets` never
writes a secret *value* even when enabled — only the container resource. `modules/cloud-sql`
documents DEV (`db-f1-micro`) vs. DEMO (`db-custom-1-3840`) tiers without creating either.
`modules/monitoring` writes one alert-policy design (5xx rate) as a template for CLOUD-07.

## Remote state

Local backend (`backend "local" {}`) for both environments — no GCS state bucket was created.
Planned future migration: a dedicated `pfi-terraform-state` bucket (not the existing artifacts
bucket, to avoid mixing application data with infra state), prefixed by environment
(`dev/`, `demo/`), with native GCS state locking. Not created this stage.

## Security checks run

- `terraform fmt -recursive` — reformatted 3 files (pure whitespace/alignment), exit 0.
- `terraform validate` — **Success** for both `dev` and `demo`, first attempt, no errors.
- `tflint` / `trivy config` / `checkov` — not installed locally; installing a new heavyweight
  scanner wasn't judged necessary to satisfy CLOUD-01's own explicit checklist (fmt + validate +
  a reviewable plan), and the instruction only asks for these "si están disponibles sin costo" —
  they aren't available here. Manual review covered: no public exposure beyond the two services
  explicitly meant to be public (Frontend, Backend); AI service `allow_unauthenticated=false`;
  no secret value anywhere in `.tf`/`.tfvars.example` files; `deletion_protection=true` by default
  on the (disabled) Cloud SQL module; no TLS configuration needed (Cloud Run terminates TLS
  automatically); service-to-service access is scoped via distinct runtime service accounts, not a
  shared identity.

## Plan status

See `CLOUD_01_TERRAFORM_PLAN.md` — **`terraform plan` was not run against live GCP** because
Application Default Credentials are not configured in this environment and completing
`gcloud auth application-default login` requires an interactive browser step this session cannot
perform. Documented as `PLAN_BLOCKED_BY_MISSING_LOCAL_CREDENTIALS`. `terraform validate` (which
needs no live credentials) already confirms the configuration is internally consistent.
