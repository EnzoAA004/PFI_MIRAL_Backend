# Cloud Roadmap

**Status: CLOUD-00 READY. CLOUD-01 READY. CLOUD-02 current (this document reflects CLOUD-02's
completion).** CLOUD-00 closed as documentation only, committed and pushed
(`782862aad65ca56c656e447ed848e6c9762cb905`, `cloud/cloud-00-architecture-audit`). CLOUD-01 turned
that design into real, `terraform validate`-passing Infrastructure as Code, committed and pushed
(Backend `eb57acd73c86dc1caf411b2acfee9f86257dcbae` on `cloud/cloud-01-terraform-foundation`;
Frontend `f003d3d9ff08aaa931d2e531c9532d98cd59b22a` on
`cloud/cloud-01-frontend-cloudrun-compat`) — see `CLOUD_01_TERRAFORM_FOUNDATION.md`,
`CLOUD_01_TERRAFORM_PLAN.md`, `CLOUD_01_COST_CHECK.md` (on the CLOUD-01 branches). CLOUD-02 built
reproducible CI for all three repos (Backend/Frontend/AI), fixed the JDK/Node/Python version drift
CLOUD-00 flagged, and validated every workflow's exact commands locally — see
`CLOUD_02_CI_PIPELINE.md`. No resource in Google Cloud has been created, modified, or destroyed by
any of the three stages. No image was pushed to any Google-side registry. Everything below CLOUD-02
is still future work, each gated by its own authorization/cost checkpoint — none of it started here.

## Staged roadmap

| Stage | Scope | Gate before starting |
|---|---|---|
| **CLOUD-01** ✅ | Terraform skeleton (7 modules + dev/demo environments), `fmt`/`validate` passing, plan-only design, Frontend Cloud Run port-compat fix | Done. Real `terraform plan` output still pending local ADC setup (`PLAN_BLOCKED_BY_MISSING_LOCAL_CREDENTIALS`). |
| **CLOUD-02** ✅ | CI workflows for Backend/Frontend/AI (test + Docker build validation, no push/deploy), JDK 17→21 (Backend, matching EXT-04B5's Dockerfile) / Node 22→24 / Python 3.11→3.12 drift fixed, Dependabot added to all three repos | Done — see `CLOUD_02_CI_PIPELINE.md`. Local validation: 1005/0/0/1 Backend, all-green Frontend, 445 passed AI (with the 3 environment-classified gaps documented, none fixed by changing product code). |
| **CLOUD-03** | Actually push images to Artifact Registry `pfi-containers` from CI | **Requires cost/authorization check** — Artifact Registry storage is billed (though negligible at current image sizes per `CLOUD_COST_MODEL.md`; the AI image was measured this stage at **2.12 GB**, larger than CLOUD-00's qualitative estimate — worth a size-reduction pass before this stage) |
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

CLOUD-00 through CLOUD-02 complete. `CLOUD-03` is the next possible step, gated by a cost/
authorization check (Artifact Registry storage billing) before any image is actually pushed.
