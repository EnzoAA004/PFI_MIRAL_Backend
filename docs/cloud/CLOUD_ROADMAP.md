# Cloud Roadmap

**Status: CLOUD-00 READY. CLOUD-01 READY. CLOUD-02 READY. CLOUD-03 READY (first image publish via
WIF succeeded for Backend + Frontend). CLOUD-04 READY (Backend + Frontend live on Cloud Run DEV,
smoke-tested including a real browser CORS/login test). CLOUD-05 — `CLOUD_05_READY_FOR_DEPLOYMENT`:
design and Terraform plan complete and pushed; real GCP deployment is a separate, not-yet-authorized
step (see "CLOUD-05 status" below). CLOUD-06 — `CLOUD_06_READY_FOR_DEPLOYMENT`: DEV→DEMO promotion
pipeline (release manifest, validate-DEV workflow, promote-DEMO workflow stopping before apply,
rollback design, corrected DEMO Terraform) designed and plan-validated, $0 new cost, nothing created
in GCP or GitHub. CLOUD-07 — `CLOUD_07_READY_FOR_DEPLOYMENT`: rollback contract + decision engine,
generalized rollback workflow (dry-run, no execute path yet), observability signals/logging/
redaction/dashboard design, alerting design (4 Terraform-modeled alert resources, all off by
default), monitoring module completed with 3 granular flags — plan-validated, $0 new cost, nothing
created in GCP or GitHub. CLOUD-08 — `CLOUD_08_READY_FOR_FREEZE`: the full remaining architecture
(CLOUD-05/06/07) is now designed, plan-validated, and consolidated into an E2E test plan, a
deployment runbook with 8 hard gates, a cost envelope, a security freeze review, a freeze manifest,
and a deployment handoff document — all documentation, no `.tf`/workflow file touched, $0 new cost.
The project's explicit strategy from here is: a future session picks up
`CLOUD_DEPLOYMENT_HANDOFF.md`, re-verifies everything fresh, and only then runs the real deploy
sequence gate-by-gate — no `terraform apply` has run for CLOUD-05/06/07.**
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

## Final status summary

Terminology distinguished on purpose: **PREPARED** (designed/coded/plan-validated) ≠ **DEPLOYED**
(a real `terraform apply` created the resource) ≠ **VALIDATED** (deployed *and* independently
smoke-tested live). **READY_FOR_FREEZE** ≠ **FINAL_FREEZE_COMPLETE** (the latter needs CLOUD-05/06/07
actually deployed and the full E2E plan actually run, neither of which has happened).

| Stage | Status |
|---|---|
| CLOUD-00 | ✅ READY |
| CLOUD-01 | ✅ READY |
| CLOUD-02 | ✅ READY |
| CLOUD-03 | ✅ DEPLOYED |
| CLOUD-04 | ✅ DEPLOYED / VALIDATED |
| CLOUD-05 | 🟦 READY_FOR_DEPLOYMENT |
| CLOUD-06 | 🟦 READY_FOR_DEPLOYMENT |
| CLOUD-07 | 🟦 READY_FOR_DEPLOYMENT |
| CLOUD-08 | 🟦 READY_FOR_FREEZE |

## Staged roadmap

| Stage | Scope | Gate before starting |
|---|---|---|
| **CLOUD-01** ✅ | Terraform skeleton (7 modules + dev/demo environments), `fmt`/`validate` passing, plan-only design, Frontend Cloud Run port-compat fix | Done. |
| **CLOUD-02** ✅ | CI workflows for Backend/Frontend/AI (test + Docker build validation, no push/deploy), JDK/Node/Python drift fixed, Dependabot added | Done. |
| **CLOUD-03** ✅ | WIF pool/provider + dedicated `pfi-github-artifact-pusher` identity + repository-scoped Artifact Registry writer binding, applied; Backend + Frontend images published via GitHub Actions OIDC/WIF (AI push deferred separately, per its 2.12 GB size) | Done for Backend/Frontend. AI publish still requires its own separate authorization. |
| **CLOUD-04** ✅ | Deployed Backend + Frontend to Cloud Run **DEV** from the exact CLOUD-03 image SHAs (no rebuild); new AI service explicitly excluded this stage; pre-existing `pfi-ai-module` confirmed untouched | Done. Both services live, smoke-tested (health, auth, CORS, real browser login) — see `CLOUD_04_CHANGE_PLAN.md` for the full apply history including two authorized follow-up fixes (`cpu_idle`, CORS real-URL). |
| **CLOUD-05** 📋 `READY_FOR_DEPLOYMENT` | Cloud SQL instance, Cloud Storage bucket audit, Secret Manager secrets | Design + Terraform + both plans (flags off/on) done and pushed, see `CLOUD_05_CHANGE_PLAN.md` and `CLOUD_05_COST_OPTIMIZATION_REVIEW.md`. **Real deployment requires explicit cost authorization (~$9.37/month, `db-f1-micro`) before apply** — see `CLOUD_05_COST_CHECK.md`. Not yet applied — **CLOUD_05_READY_FOR_DEPLOYMENT, not CLOUD_05_MANAGED_PERSISTENCE_READY** (that status is reserved for after a real, verified GCP apply). |
| **CLOUD-06** 📋 `READY_FOR_DEPLOYMENT` | CD promotion DEV → DEMO (manual approval gate, same-image redeploy) | Design + Terraform + workflows + plan done, see `CLOUD_06_CHANGE_PLAN.md`. DEMO Terraform plan: 5 to add, 0 to change, 0 to destroy (Cloud Run services + invoker bindings + 1 SA only). **Real DEMO deployment requires its own future authorization** — no `terraform apply`, no workflow has ever run for real. `CLOUD_06_READY_FOR_DEPLOYMENT`, not `CLOUD_06_DEV_TO_DEMO_READY` (DEMO doesn't exist yet). |
| **CLOUD-07** 📋 `READY_FOR_DEPLOYMENT` | Rollback procedure wired into CI, monitoring dashboards, alert policies | Design + Terraform + workflow + plan done, see `CLOUD_07_CHANGE_PLAN.md`. Monitoring preview plan: 4 to add, 0 to change, 0 to destroy (alert policies + uptime check only). **Real rollback execution and monitoring activation require their own future authorization** — no `terraform apply`, no traffic ever shifted, no alert/uptime resource created. `CLOUD_07_READY_FOR_DEPLOYMENT`, not `CLOUD_07_OBSERVABILITY_READY`. |
| **CLOUD-08** 📋 `READY_FOR_FREEZE` | Final cloud E2E test plan, deployment runbook, cost envelope, security freeze review, freeze manifest, deployment handoff — all documentation, no `.tf`/workflow file touched | Design complete, see the six `CLOUD_08_*.md` + `CLOUD_DEPLOYMENT_HANDOFF.md` docs. **`CLOUD_08_READY_FOR_FREEZE`, not `CLOUD_08_FINAL_FREEZE_COMPLETE`** — no real deploy of CLOUD-05/06/07 has occurred, so the architecture is not yet actually frozen in a deployed sense, only fully specified and ready to be. |

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

**CLOUD-06 status: `CLOUD_06_READY_FOR_DEPLOYMENT` — design/plan complete, real DEMO deployment not
yet authorized, $0 new cost incurred.**

- **DESIGN/PLAN: READY.** Release manifest schema (`pfi-cloud-release.v1`), `cloud-06-validate-dev.yml`
  (image-exists + digest + Cloud-Run-state + smoke checks, uploads a signed manifest), `cloud-06-
  promote-demo.yml` (re-verifies the manifest's digests against live Artifact Registry, runs
  `terraform plan` for DEMO, explicitly has no apply step), and `cloud-06-rollback-demo.yml`
  (dry-run only — prints the traffic-shift command, never executes it). All three workflows parse as
  valid YAML; the two new shell scripts (`scripts/cloud/verify-image-digest.sh`,
  `scripts/cloud/cloud-06-smoke.sh`) pass `bash -n` and were exercised against synthetic/mocked
  `gcloud` output (match/mismatch/`latest`-rejection all behave correctly) — no cloud writes involved.
  `environments/demo`'s Terraform had four real, previously-undetected bugs (wrong health check path,
  missing Frontend `port`/`cpu_idle`, an unconditionally-created AI service, an unconditional Cloud
  SQL IAM grant) — all fixed to mirror DEV's CLOUD-04/05 patterns, yielding a clean
  `terraform plan`: **5 to add, 0 to change, 0 to destroy** (2 Cloud Run services, 2 invoker
  bindings, 1 new service account — nothing else). See `CLOUD_06_CHANGE_PLAN.md`.
- **REAL DEMO DEPLOYMENT: NOT AUTHORIZED.** No workflow has ever run for real (two proposed CI/
  Terraform-plan identities — see `CLOUD_06_SECURITY_REVIEW.md` — don't exist yet, so they couldn't
  run even if triggered). No GitHub Environment created. No `terraform apply`. Zero new GCP resources,
  zero new cost.

Cloud branches for CLOUD-06 (`cloud/cloud-06-promotion-backend`) were created from CLOUD-05's final
pushed HEAD (`b456333`), per explicit instruction, not from an earlier CLOUD-05 commit or CLOUD-04.

**CLOUD-07 status: `CLOUD_07_READY_FOR_DEPLOYMENT` — design/plan complete, no rollback ever executed,
no monitoring resource created, $0 new cost incurred.**

- **DESIGN/PLAN: READY.** Rollback contract (`pfi-cloud-rollback.v1`), a generalized
  `cloud-07-rollback.yml` (verifies both revisions exist via `verify-cloudrun-revision.sh`, checks
  candidate health, decides `ROLLBACK_REQUIRED`/`NO_ROLLBACK` via `rollback-decision.sh`, prints but
  never runs the traffic-shift command — `execute=true` has no effect yet, by design), observability
  signals/logging-field/redaction-policy/dashboard design (`CLOUD_07_OBSERVABILITY.md`), 7 alert
  scenarios A-G designed (4 Terraform-modeled: Backend/Frontend 5xx, Backend uptime-check-failure +
  the uptime check itself; 3 deferred until Cloud SQL is real: E/F/G). `modules/monitoring` rewritten
  with 3 granular flags (`enable_alerts`/`enable_uptime_checks`/`enable_notification_channels`, all
  default `false`). Both required plans re-verified: flags off → 0/0/0 against live DEV state; flags
  on → **4 to add, 0 to change, 0 to destroy** (exactly the 4 monitoring resources, nothing else — no
  Cloud SQL, secrets, new Cloud Run, unrelated IAM, Artifact Registry, WIF, storage, or AI). See
  `CLOUD_07_CHANGE_PLAN.md`. Two new shell scripts (`verify-cloudrun-revision.sh`,
  `rollback-decision.sh`) validated via `bash -n` plus all of Phase 14's required synthetic failure
  simulations (candidate health fail, digest mismatch, missing revision, error-rate threshold
  exceeded, all-healthy) — all produced the correct verdict.
- **REAL ROLLBACK/MONITORING: NOT AUTHORIZED.** No workflow has ever run for real (the proposed
  `pfi-github-rollback-executor` identity — see `CLOUD_07_SECURITY_REVIEW.md` — doesn't exist, and
  `cloud-07-rollback.yml` has no traffic-update step regardless). `sqladmin.googleapis.com` and
  `secretmanager.googleapis.com` remain disabled (unrelated to this stage, but reconfirmed); zero
  alert policies, zero uptime checks, zero notification channels, zero dashboards exist (confirmed
  via the Monitoring REST API). Zero new GCP resources, zero new cost.

Cloud branch for CLOUD-07 (`cloud/cloud-07-observability-rollback`) was created from CLOUD-06's final
pushed HEAD (`d05c85a96ee19529594b47ee35978518ec5b8c6a`), per explicit instruction.

**CLOUD-08 status: `CLOUD_08_READY_FOR_FREEZE` — documentation-only, no `.tf`/workflow file touched,
$0 new cost, no cloud write.**

- **Real deployed inventory** (re-verified live, read-only, this stage): `pfi-backend-dev` and
  `pfi-frontend-dev` (Cloud Run, both `Ready`), `pfi-github-artifact-pusher` + `github-pool` +
  `github-provider` (WIF, all `ACTIVE`), `pfi-containers` (Artifact Registry, unchanged),
  `pfi-ai-module` (pre-existing, untouched), `pfi-rm-lumbar-artifacts-2026-ef` (bucket, untouched).
  Cloud SQL = 0, Secret Manager = 0, Monitoring = 0 — all reconfirmed via read-only `gcloud` this
  stage, matching every prior stage's own finding.
- **Designed-but-not-deployed inventory**: Cloud SQL `pfi-postgres-dev`, 2 Secret Manager
  containers, DEMO's `pfi-backend-demo`/`pfi-frontend-demo`/runtime SA, 4 monitoring resources per
  environment — all plan-validated (real `terraform plan` against live GCP for every one), none
  applied.
- **Deliverables**: `CLOUD_08_FINAL_E2E_PLAN.md` (full E2E sequence + 8 failure scenarios, none
  executed), `CLOUD_08_DEPLOYMENT_RUNBOOK.md` (ordered deploy sequence + 8 hard gates + per-stage
  runbooks for CLOUD-05/06/07, none executed), `CLOUD_08_COST_ENVELOPE.md` (current/future DEV/DEMO/
  monitoring costs + 4 consolidated scenarios + shutdown plan), `CLOUD_08_SECURITY_FREEZE.md` (14
  findings reviewed, 0 new BLOCKERs, 2 WARNINGs, 2 ACCEPTED_RISKs, none newly introduced by this
  session's work), `CLOUD_08_FREEZE_MANIFEST.md` (`pfi-cloud-freeze.v1` — every repo/branch/SHA/
  image/digest/deploy-status snapshot, no secret value), `CLOUD_DEPLOYMENT_HANDOFF.md` (everything a
  future session needs to resume CLOUD-05 without re-deriving anything), `CLOUD_08_CHANGE_PLAN.md`
  (this stage's own `fmt`/`validate` results + the consolidated plan-shape table + final status
  matrix across CLOUD-00 through CLOUD-08).
- **Explicitly not claimed**: `CLOUD_08_FINAL_FREEZE_COMPLETE` — that status is reserved for after
  CLOUD-05/06/07 are actually applied and the full E2E plan has actually been run against a real
  DEMO, neither of which has happened.

Cloud branch for CLOUD-08 (`cloud/cloud-08-final-freeze`) was created from CLOUD-07's exact pushed
HEAD (`1da60ca2ba7a52352c4bf71aaaaa32e075141c4e`), per explicit instruction. Nothing committed yet —
left `READY_TO_COMMIT`.
