# Cloud Roadmap

CLOUD-00 (this stage) is complete as documentation only. Everything below is future work, each
stage gated by its own authorization/cost checkpoint — none of it is started by this document.

## Staged roadmap

| Stage | Scope | Gate before starting |
|---|---|---|
| **CLOUD-01** | Terraform skeleton (modules + environments per `CLOUD_CICD_DESIGN.md`), `terraform validate`/`terraform plan` only | None — no resource created by `validate`/`plan`. Still: confirm with the user before writing files outside `docs/`. |
| **CLOUD-02** | CI workflow changes: fix JDK/Python version pins, add Docker build validation on PRs | None — no deployment, no registry push |
| **CLOUD-03** | Actually push images to Artifact Registry `pfi-containers` from CI | **Requires cost/authorization check** — Artifact Registry storage is billed (though negligible at current image sizes per `CLOUD_COST_MODEL.md`) |
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

Documentation-only. No stage past CLOUD-00 has been started. `CLOUD-01` is the next possible step,
and even it requires the user's go-ahead before any file is written outside `docs/cloud/`.
