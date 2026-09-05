# Cloud Roadmap

**Status: CLOUD-00 READY. CLOUD-01 READY. CLOUD-02 READY. CLOUD-03 READY (first image publish via
WIF succeeded for Backend + Frontend). CLOUD-04 READY (Backend + Frontend live on Cloud Run DEV,
smoke-tested including a real browser CORS/login test). CLOUD-05 — `CLOUD_05_READY_FOR_DEPLOYMENT`:
design and Terraform plan complete and pushed; real GCP deployment is a separate, not-yet-authorized
step (see "CLOUD-05 status" below). The project's explicit strategy from here is: finish designing
the full cloud architecture (CLOUD-05 through CLOUD-08) in plan-only mode before doing the complete
deploy and starting to measure real cost — no `terraform apply` runs until that full-architecture
review is done and cost is authorized.**
CLOUD-00 closed as documentation only, committed and pushed
(`782862aad65ca56c656e447ed848e6c9762cb905`, `cloud/cloud-00-architecture-audit`). CLOUD-01 turned
that design into real, `terraform validate`-passing Infrastructure as Code, committed and pushed
(Backend `eb57acd73c86dc1caf411b2acfee9f86257dcbae` on `cloud/cloud-01-terraform-foundation`;
Frontend `f003d3d9ff08aaa931d2e531c9532d98cd59b22a` on `cloud/cloud-01-frontend-cloudrun-compat`).
CLOUD-02 built reproducible CI for all three repos, committed and pushed (Backend
`56689fc173a0f6082981485613c96dd7ed304290`; Frontend `cbf48d2e5ca47d411e54e3bd638b1ab20ae03cd7`; AI
`fa7eccc83feb204eb7650d7b467c6530122e42cb`). CLOUD-03 designed real Workload Identity Federation and
three manual-publish workflows, then — under explicit authorization — successfully published the
first real images: Backend `backend:dee0826c9e1121e348192058acde841b6f933608` and Frontend
`frontend:1c77231e2e1833bada6dc1c0f4f622f1d32e3470`, both via GitHub Actions OIDC/WIF, zero JSON
keys, verified in Artifact Registry (digests, sizes, timestamps recorded in the CLOUD-03 publish
report). **CLOUD-04 deployed those exact two images (no rebuild) to Cloud Run DEV and is complete** — see
`CLOUD_04_CLOUD_RUN_DEV.md`, `CLOUD_04_SECURITY_REVIEW.md`, `CLOUD_04_COST_CHECK.md`,
`CLOUD_04_CHANGE_PLAN.md`. `pfi-backend-dev` and `pfi-frontend-dev` are both live, smoke-tested
(including a real browser login/CORS smoke test), `pfi-ai-module` untouched throughout.

## Staged roadmap

| Stage | Scope | Gate before starting |
|---|---|---|
| **CLOUD-01** ✅ | Terraform skeleton (7 modules + dev/demo environments), `fmt`/`validate` passing, plan-only design, Frontend Cloud Run port-compat fix | Done. |
| **CLOUD-02** ✅ | CI workflows for Backend/Frontend/AI (test + Docker build validation, no push/deploy), JDK/Node/Python drift fixed, Dependabot added | Done. |
| **CLOUD-03** ✅ | WIF pool/provider + dedicated `pfi-github-artifact-pusher` identity + repository-scoped Artifact Registry writer binding, applied; Backend + Frontend images published via GitHub Actions OIDC/WIF (AI push deferred separately, per its 2.12 GB size) | Done for Backend/Frontend. AI publish still requires its own separate authorization. |
| **CLOUD-04** ✅ | Deployed Backend + Frontend to Cloud Run **DEV** from the exact CLOUD-03 image SHAs (no rebuild); new AI service explicitly excluded this stage; pre-existing `pfi-ai-module` confirmed untouched | Done. Both services live, smoke-tested (health, auth, CORS, real browser login) — see `CLOUD_04_CHANGE_PLAN.md` for the full apply history including two authorized follow-up fixes (`cpu_idle`, CORS real-URL). |
| **CLOUD-05** 📋 `READY_FOR_DEPLOYMENT` | Cloud SQL instance, Cloud Storage bucket audit, Secret Manager secrets | Design + Terraform + both plans (flags off/on) done and pushed, see `CLOUD_05_CHANGE_PLAN.md` and `CLOUD_05_COST_OPTIMIZATION_REVIEW.md`. **Real deployment requires explicit cost authorization (~$9.37/month, `db-f1-micro`) before apply** — see `CLOUD_05_COST_CHECK.md`. Not yet applied — **CLOUD_05_READY_FOR_DEPLOYMENT, not CLOUD_05_MANAGED_PERSISTENCE_READY** (that status is reserved for after a real, verified GCP apply). |
| **CLOUD-06** | CD promotion DEV → DEMO (manual approval gate, same-image redeploy) | Depends on CLOUD-03/04/05 being live. May be designed/planned now (Terraform, GitHub Actions, `terraform validate`/`plan`, docs) under the same plan-only policy — **no apply**. |
| **CLOUD-07** | Rollback procedure wired into CI, monitoring dashboards, alert policies | Depends on CLOUD-04+. May be designed/planned now under the same plan-only policy — **no apply**. |
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
(shared-core) for DEV and a small dedicated tier for DEMO. **Why**: real persisted data (studies,
reviews, corrections) needs a real database for anything beyond a smoke test, managed
backups/IAM-auth reduce operational burden, and the cost is bounded and known
(`CLOUD_COST_MODEL.md`) even though it is the one always-on line item.
**Correction (CLOUD-04)**: `SecurityStartupValidator` does *not* fail closed generally — it only
activates under the `production`/`prod` Spring profile, which DEV never sets. The actual DB
dependency is `pfi.persistence.mode`: `postgres` eagerly requires a reachable database at Spring
context startup (`PostgresStudyRepository`'s constructor), while `memory` (the image's own default)
starts cleanly with no database at all — this is how CLOUD-04 deploys a working DEV smoke
environment before Cloud SQL exists. Cloud SQL remains the CLOUD-05 target for real, persisted DEV
data; it is not a startup requirement in general. **Revisit if**: DEV-only cost pressure ever
justifies a container-based ephemeral Postgres for pure CI integration testing (separate from the
persistent DEV Cloud SQL instance) — a refinement, not a blocker.

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

CLOUD-00 through CLOUD-04 complete and pushed. CLOUD-03 applied (WIF live) and its first Backend +
Frontend image publish succeeded. CLOUD-04 applied: `pfi-backend-dev` and `pfi-frontend-dev` are
both live on Cloud Run DEV, running the exact CLOUD-03 image digests, smoke-tested green including
a real browser login/CORS test — see `CLOUD_04_CHANGE_PLAN.md` for the full apply history. Only
three Cloud Run services exist in the project: `pfi-ai-module` (pre-existing, untouched),
`pfi-backend-dev`, `pfi-frontend-dev`.

**CLOUD-05 status: `CLOUD_05_READY_FOR_DEPLOYMENT` — design/plan complete and pushed, real deployment
not yet authorized.** Two states, kept explicitly distinct:

- **DESIGN/PLAN: READY.** Selected configuration — PostgreSQL 15, Cloud SQL **Enterprise** edition,
  tier **`db-f1-micro`** (shared-core), region `us-central1`, availability **ZONAL**, disk **10 GB**
  PD_SSD, backups **false** (DEV), HA **false**, PITR **false**, `deletion_protection` **true** — plus
  Secret Manager (2 secret containers, no values) and a Cloud SQL Auth Proxy sidecar in the Backend's
  Cloud Run service. Committed and pushed on `cloud/cloud-05-managed-persistence`
  (`d4d31d4` design, `c5c1d23` cost/tier correction — both on `origin`). `terraform fmt`/`validate`
  pass, both required plans reviewed and re-verified after the tier correction
  (`enable_cloud_sql=false` → 0/0/0; `enable_cloud_sql=true`+`enable_secrets=true` → 7 to add, 1 to
  change, 0 to destroy, scoped exactly to Cloud SQL + secrets + IAM + one Backend Cloud Run revision
  update, same image digest, zero Frontend/AI/other impact). 17 migrations validated end-to-end
  against a clean local Postgres using the exact published Backend image (no rebuild needed).
  Estimated running cost: **~$9.37/month** (`db-f1-micro`, corrected from an earlier, wrong
  `db-custom-1-3840` ~$51/month estimate — see `CLOUD_05_COST_OPTIMIZATION_REVIEW.md`).
- **REAL DEPLOYMENT: PENDING COST AUTHORIZATION.** `sqladmin.googleapis.com` and
  `secretmanager.googleapis.com` remain disabled; zero Cloud SQL instances, zero Secret Manager
  secrets, zero new IAM bindings exist in GCP for this stage. **No cost has been incurred yet** —
  the ~$9.37/month figure is an estimate for an instance that does not exist. `terraform apply` has
  never been run for this stage.

**Project strategy from this point**: finish designing the full remaining cloud architecture
(CLOUD-06, CLOUD-07, and CLOUD-08's design elements) in plan-only mode — Terraform, GitHub Actions,
local tests, `terraform validate`/`plan`, documentation — with no `terraform apply` for any of them,
before doing one complete deploy and starting to measure real cloud cost. `CLOUD_05_MANAGED_PERSISTENCE_READY`
will only be declared after a separately authorized apply cycle: cost approved, Cloud SQL created,
secrets created securely out-of-band, Backend connected, persistence verified across a real restart,
and no plaintext secret leak confirmed.
