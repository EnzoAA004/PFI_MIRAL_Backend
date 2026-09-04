# Cloud Roadmap

**Status: CLOUD-00 READY. CLOUD-01 READY. CLOUD-02 READY. CLOUD-03 current — design/plan complete,
awaiting explicit authorization before any cloud write.** CLOUD-00 closed as documentation only,
committed and pushed (`782862aad65ca56c656e447ed848e6c9762cb905`,
`cloud/cloud-00-architecture-audit`). CLOUD-01 turned that design into real,
`terraform validate`-passing Infrastructure as Code, committed and pushed (Backend
`eb57acd73c86dc1caf411b2acfee9f86257dcbae` on `cloud/cloud-01-terraform-foundation`; Frontend
`f003d3d9ff08aaa931d2e531c9532d98cd59b22a` on `cloud/cloud-01-frontend-cloudrun-compat`). CLOUD-02
built reproducible CI for all three repos, committed and pushed (Backend
`56689fc173a0f6082981485613c96dd7ed304290`; Frontend `cbf48d2e5ca47d411e54e3bd638b1ab20ae03cd7`; AI
`fa7eccc83feb204eb7650d7b467c6530122e42cb`). **CLOUD-03 extends the CLOUD-01 Terraform IAM/Artifact
Registry design for real Workload Identity Federation, writes the three manual-publish GitHub
Actions workflows, and stops before any `terraform apply` or `gcloud` write** — see
`CLOUD_03_ARTIFACT_REGISTRY_WIF.md`, `CLOUD_03_SECURITY_REVIEW.md`, `CLOUD_03_COST_CHECK.md`,
`CLOUD_03_CHANGE_PLAN.md`. No resource in Google Cloud has been created, modified, or destroyed by
any of the four stages. No image has been pushed to any Google-side registry.

## Staged roadmap

| Stage | Scope | Gate before starting |
|---|---|---|
| **CLOUD-01** ✅ | Terraform skeleton (7 modules + dev/demo environments), `fmt`/`validate` passing, plan-only design, Frontend Cloud Run port-compat fix | Done. |
| **CLOUD-02** ✅ | CI workflows for Backend/Frontend/AI (test + Docker build validation, no push/deploy), JDK/Node/Python drift fixed, Dependabot added | Done. |
| **CLOUD-03** 🔶 current | WIF pool/provider + dedicated `pfi-github-artifact-pusher` identity + repository-scoped Artifact Registry writer binding, designed and `terraform validate`-passing; three `workflow_dispatch`-only publish workflows written | **Design/plan complete. Awaiting explicit authorization** for: (1) enabling `iam.googleapis.com`/`iamcredentials.googleapis.com`/`sts.googleapis.com`, (2) the scoped `terraform apply`, (3) the first real image push per repo (AI's push authorized separately from Backend/Frontend, per its 2.12 GB size) — see `CLOUD_03_CHANGE_PLAN.md`'s pre-authorization review. |
| **CLOUD-04** | Deploy Backend + Frontend (+ AI service, workload-classified) to Cloud Run **DEV** | **Requires explicit authorization before first `gcloud run deploy` / `terraform apply` that creates a Cloud Run service** — also requires resolving what to do with the pre-existing `pfi-ai-module` service found in the audit (reuse vs. replace vs. leave alone) |
| **CLOUD-05** | Cloud SQL instance, Cloud Storage bucket(s) for model artifacts/outputs, Secret Manager secrets | **Requires explicit cost authorization** — Cloud SQL is the one always-on cost identified in `CLOUD_COST_MODEL.md` |
| **CLOUD-06** | CD promotion DEV → DEMO (manual approval gate, same-image redeploy) | Depends on CLOUD-03/04/05 being live |
| **CLOUD-07** | Rollback procedure wired into CI, monitoring dashboards, alert policies | Depends on CLOUD-04+ |
| **CLOUD-08** | Final cloud E2E test across DEV/DEMO, architecture freeze document | Last stage — declares the cloud architecture stable |

No stage in this roadmap includes GKE or Argo CD (see `ADR-001`) or a GPU/Qwen deployment (EXT-06
stays blocked pending separate authorization, unrelated to this roadmap's gates).

## Architecture Decision Records

### ADR-001: Cloud Run over GKE

**Decision**: use Cloud Run for all three services (Backend, Frontend, AI-CPU-workloads) instead of
GKE. **Why**: three stateless, independently-scalable HTTP services with no need for pod-level
orchestration, sidecar patterns, or custom scheduling — Cloud Run gives scale-to-zero, per-request
billing, and zero cluster-management overhead for exactly this shape of workload. GKE's value
(fine-grained scheduling, StatefulSets, service mesh, custom autoscaling) has no current use case
here. **Revisit if**: the AI workload grows into something needing GPU pools with custom scheduling,
or the number of independently-versioned microservices grows enough that Cloud Run's simpler
per-service model becomes unwieldy.

### ADR-002: Terraform for IaC

**Decision**: manage all cloud resources (once created) via Terraform, module/environment structure
per `CLOUD_CICD_DESIGN.md`. **Why**: reproducibility across DEV/DEMO, a reviewable diff for every
infra change (via `terraform plan` in CI), and avoiding the drift risk of manual `gcloud` commands
building up undocumented state — especially relevant given this audit already found manually-created
resources (`pfi-containers`, `pfi-ai-module`, the bucket, four service accounts) with no IaC record.
**Revisit if**: never, for a project this size — Terraform is the standard default here.

### ADR-003: GitHub Actions + Workload Identity Federation

**Decision**: GitHub Actions as the sole CI/CD runner, authenticating to GCP exclusively via
Workload Identity Federation, never a downloaded service-account JSON key. **Why**: the repos
already live on GitHub with working Actions workflows (Frontend already pushes images to GHCR);
WIF eliminates the single highest-risk secret-management failure mode (a leaked long-lived key) at
no extra cost. **Revisit if**: a self-hosted runner requirement emerges (none identified today).
**CLOUD-03 update**: the design is now concrete — a single dedicated `pfi-github-artifact-pusher`
identity, an explicit 3-repo attribute-condition allowlist (never `repository_owner`-wide), and
repository-scoped (not project-wide) Artifact Registry IAM. Not yet applied.

### ADR-004: Cloud SQL strategy

**Decision**: Cloud SQL PostgreSQL is the target for DEV and DEMO, sized `db-f1-micro`
(shared-core) for DEV and a small dedicated tier for DEMO. **Why**: the Backend already fails
closed without a real Postgres connection (`SecurityStartupValidator`), managed backups/IAM-auth
reduce operational burden, and the cost is bounded and known (`CLOUD_COST_MODEL.md`) even though it
is the one always-on line item. **Revisit if**: DEV-only cost pressure ever justifies a
container-based ephemeral Postgres for pure CI integration testing (separate from the persistent
DEV Cloud SQL instance) — a refinement, not a blocker.

### ADR-005: AI compute strategy

**Decision**: classify AI workloads before choosing compute — CPU-only product inference
(existing classifiers, EXT-03 Grad-CAM) goes to Cloud Run; a real Qwen LLM (EXT-06) requires a GPU
and stays fully blocked until separately authorized; sustained CPU-heavy batch work, if it ever
materializes, gets evaluated against Compute Engine rather than assumed to fit Cloud Run's
per-request billing model. **Why**: avoids the mistake of routing every "AI" workload to the same
infra by label rather than by actual resource profile. **Revisit if**: EXT-06 is ever unblocked by
explicit authorization — that triggers a new, separate cost/architecture decision, not an automatic
extension of this ADR.

### ADR-006: DEV/DEMO environments (no PROD)

**Decision**: exactly two cloud environments, DEV and DEMO, with DEMO always deploying the
identical image already validated in DEV (no rebuild). No PROD environment exists in this roadmap.
**Why**: this is a thesis/demo project, not a live production service with real end users yet —
introducing a third environment (or a PROD-grade SLA target) would add cost and process overhead
with no corresponding need. **Revisit if**: the project moves toward a real production launch with
actual end users, at which point a genuine PROD environment (and its own security/rollback/
observability requirements) would need its own scoped design pass.

## Status

CLOUD-00 through CLOUD-02 complete and pushed. CLOUD-03's design, Terraform, and workflows are
complete and validated but **not applied** — see `CLOUD_03_CHANGE_PLAN.md` for the exact
pre-authorization review. No stage past CLOUD-02 has touched Google Cloud.
