# CLOUD-04 — Cloud Run DEV Deployment

**Status: `terraform apply cloud04.tfplan` executed under explicit authorization. Backend fully
deployed, live, and smoke-tested green. Frontend blocked by a live GCP API validation rule not
caught by `terraform plan` (see `CLOUD_04_CHANGE_PLAN.md` "Apply result") — awaiting a fresh,
explicit authorization for the minimal fix before retrying. No unintended resource was created;
`pfi-ai-module` untouched.**

## Live results (Backend)

| Field | Value |
|---|---|
| Service URL | `https://pfi-backend-dev-7cxg46zwea-uc.a.run.app` (also `https://pfi-backend-dev-810538429027.us-central1.run.app`) |
| Revision | `pfi-backend-dev-00001-6qw` |
| Image (tag) | `backend:dee0826c9e1121e348192058acde841b6f933608` |
| Image digest (live, `gcloud run revisions describe`) | `sha256:75106a69465d82f5733afe9fe0bb2392dbc1f154687ac535ec864c6e80b85e24` — **matches the CLOUD-03 publish digest exactly** |
| Runtime SA | `pfi-backend-runtime@pfi-asplanatti-fabrello-v1.iam.gserviceaccount.com` |
| min/max instances | 0 / 2 |
| CPU / memory | 1 / 512Mi |
| `cpu-throttling` | `false` (GCP default when unset — the exact reason Frontend's 256Mi failed, see change plan) |

### Backend smoke test (executed)

```
GET  /api/system/health           → 200 {"status":"ok"}
GET  /api/studies (no token)      → 401
POST /api/auth/demo-doctor        → 200, valid JWT issued
GET  /api/studies   (with JWT)    → 200
GET  /api/auth/me   (with JWT)    → 200, returns demo doctor profile
```

All green. Confirms: health minimal/public, application endpoints fail-closed without a token,
demo auth issues a usable JWT, an authenticated basic endpoint round-trips correctly — all with
`PFI_PERSISTENCE_MODE=memory` and no AI dependency, exactly as designed.

### Logging (Cloud Logging, read-only)

`gcloud logging read` for `pfi-backend-dev` shows structured request logs
(`TraceIdFilter: event=http_request ... status=200/401 ... outcome=success/client_error`) for every
smoke call, correct trace IDs, correct actor/roles once authenticated. **Zero `severity>=ERROR`
log entries** in the service's lifetime so far — no crash loop, no port-binding error, no
permission-denied, no fatal DB/AI dependency error.

## Frontend — blocked, not yet live

See `CLOUD_04_CHANGE_PLAN.md` "Apply result" for the full root-cause diagnosis. Summary: Cloud Run
rejected creation with `memory < 512Mi is not supported with cpu always allocated (unthrottled)` —
a live API admission rule, invisible to `terraform plan`. `pfi-frontend-dev` does not exist yet;
nothing else was affected.

## Objective

Deploy the exact Backend/Frontend images already published to Artifact Registry in CLOUD-03 to two
new Cloud Run **DEV** services — `pfi-backend-dev` and `pfi-frontend-dev` — with no rebuild, no
Cloud SQL, no new AI service, and the pre-existing `pfi-ai-module` service left untouched.

## Images (no rebuild)

```
Backend:  us-central1-docker.pkg.dev/pfi-asplanatti-fabrello-v1/pfi-containers/backend:dee0826c9e1121e348192058acde841b6f933608
Frontend: us-central1-docker.pkg.dev/pfi-asplanatti-fabrello-v1/pfi-containers/frontend:1c77231e2e1833bada6dc1c0f4f622f1d32e3470
```

Both digests were verified in Artifact Registry during CLOUD-03's first publish and are re-verified
below.

## Blockers found and resolved (Terraform-only, no app code, no image rebuild)

### 1. Backend health check path was wrong, not broken

CLOUD-01's audit found `GET /actuator/health → 401` and the task assumed a code fix was needed. It
was not. Verified directly in the Backend repo:

- `pom.xml` has **no** Spring Boot Actuator dependency at all — `/actuator/health` was never a real
  endpoint. The 401 is `AuthFilter` failing closed on an unrecognized path, by design (`AuthFilter`
  has no wildcard "public" fallback).
- A minimal, already-existing, already-tested public liveness endpoint already exists:
  `GET /api/system/health` → `{"status":"ok"}`, no DB/AI/secrets/infra detail
  (`SystemController#health`, listed in `AuthFilter.PUBLIC_LIVENESS_PATHS`, with an explicit
  javadoc: *"Only a minimal, no-detail liveness endpoint is anonymously reachable"*). Already
  covered by `SecurityAuthorizationIntegrationTest#anonymousSystemHealthIsPublicAndMinimal` (200 +
  minimal body) and `#adminDiagnosticsRequiresAuthentication`/`#adminDiagnosticsForbidden` (the
  richer `/api/system/diagnostics` stays ADMIN-only).

**No `cloud/cloud-04-backend-health` branch was created.** `infra/terraform/environments/dev/main.tf`
now points `health_check_path` at `/api/system/health` instead.

### 2. Frontend Cloud Run port mismatch (would have failed to start)

The `cloud-run-service` module defaults `port = 8080`. The Frontend image's nginx
(`docker/nginx.conf`) listens on **80**. Left as-is, Cloud Run would target port 8080 for the
startup probe and all traffic, nothing would answer, and the revision would never become ready.
Fixed by passing `port = 80` explicitly in the `frontend_cloud_run` module call — no image change.

### 3. Backend persistence mode would have crash-looped the container

`infra/terraform/environments/dev/main.tf` previously set `PFI_PERSISTENCE_MODE=postgres` with no
Cloud SQL instance and no `DATABASE_URL`. `PostgresStudyRepository` is
`@ConditionalOnProperty(havingValue="postgres")` and eagerly opens a JDBC connection and runs
migrations in its constructor — with no reachable database this fails Spring context startup
entirely, regardless of any profile.

`SecurityStartupValidator` (which *does* fail closed on missing Postgres) only activates when
`SPRING_PROFILES_ACTIVE` contains `production`/`prod` — this DEV deployment never sets that
profile, so it is a no-op here either way. The Backend image's own `Dockerfile` already defaults
`PFI_PERSISTENCE_MODE=memory`; Terraform now sets this explicitly (rather than relying silently on
the image default) so the DB strategy decision is visible in the infra that deploys it.

**Database strategy chosen: Option A** — Backend starts with `InMemoryStudyRepository`
(`pfi.persistence.mode=memory`, the codebase's own default/test mode), sufficient for health, auth
smoke (`/api/auth/demo-doctor`), and basic endpoint smoke. No Cloud SQL instance is created this
stage (`enable_cloud_sql` stays `false`). `CLOUD_04_BACKEND_BLOCKED_BY_DATABASE` does **not**
apply — the Backend is not blocked, it runs in its already-supported memory mode.

### 4. AI strategy

`pfi.ai-service.base-url` (`PFI_AI_SERVICE_URL`) is a plain HTTP client base URL, read lazily per
request — nothing pings it at startup. Left at its image default
(`http://host.docker.internal:8000`, harmless/unreachable in Cloud Run); AI-dependent endpoints will
fail per-call, which is outside this stage's smoke scope (health/auth/basic endpoint only). No new
AI Cloud Run service is created (`enable_ai_service` stays `false`, see below); `pfi-ai-module` is
not reused or touched.

## Terraform changes (`infra/terraform/environments/dev/`)

- `variables.tf`: new `enable_ai_service` (default `false`), mirroring `enable_cloud_sql`/
  `enable_monitoring`.
- `main.tf`:
  - `module.ai_cloud_run` gated behind `count = var.enable_ai_service ? 1 : 0` (previously
    unconditional — would have planned a brand-new `pfi-ai-service-dev` Cloud Run service every run).
  - `module.iam`'s `project_iam_bindings` (the `backend_runtime → roles/cloudsql.client` grant)
    gated behind `var.enable_cloud_sql` — no IAM change plans until Cloud SQL actually exists.
  - `backend_cloud_run`: `health_check_path` fixed, `env_vars` set to
    `PFI_PERSISTENCE_MODE=memory`, `PFI_AUTH_DEMO_ENABLED=true` (DEV-only, enables the demo-login
    smoke path), `PFI_CORS_ALLOWED_ORIGINS` extended with the predicted Frontend URL.
  - `frontend_cloud_run`: `port = 80`, `health_check_path = "/"` added.
  - New `data "google_project" "current"` + `local.frontend_predicted_url` — Cloud Run v2's default
    URL is deterministic (`https://<service>-<project-number>.<region>.run.app`, confirmed against
    the live `pfi-ai-module` service's own second URL), used to give the Backend a CORS origin for
    the Frontend without creating a circular module dependency (Frontend already depends on
    `module.backend_cloud_run.url` for `BACKEND_URL`).
- `outputs.tf`: `ai_url` now conditional on `enable_ai_service` (was an unconditional reference into
  a module that may not be instantiated).

No Frontend-repo change was needed or made — the Frontend repo has no Terraform of its own; all
infra lives in this Backend repo, and the Frontend image/entrypoint/env.js mechanism already works
exactly as required (`BACKEND_URL` env var → `docker/entrypoint.sh` → `env.js`, no rebuild). No
`cloud/cloud-04-cloudrun-frontend` branch was created for this reason.

## Networking

- Frontend: public (`allow_unauthenticated = true`, `ingress = INGRESS_TRAFFIC_ALL`).
- Backend: public endpoint (`allow_unauthenticated = true`) so the browser can reach it directly —
  but this only grants `roles/run.invoker` (permission to send an HTTP request to the container).
  Every application endpoint except `/api/system/health` and the six `/api/auth/*` entry points
  still requires a valid Bearer JWT via `AuthFilter`; `/api/system/diagnostics` and `/warmup` still
  require the ADMIN role. Public Cloud Run ingress and the app's own JWT gate are independent
  layers — this stage keeps both.
- AI: no public change (module not instantiated).

## IAM

- `pfi-backend-runtime` (pre-existing, `EXISTING_EXTERNAL` in Terraform) — reused as-is, no new
  grant this stage (`cloudsql.client` stays gated off).
- `pfi-frontend-runtime-dev` (**new**) — created by this plan, zero project-level IAM bindings
  (Frontend needs no GCP permissions to serve static files and proxy env.js).
- The CI/CD identity `pfi-github-artifact-pusher` is never used as a runtime identity — confirmed
  distinct from both runtime service accounts.

## Terraform plan (real, against live GCP)

```
$ terraform init      # Success
$ terraform validate  # Success! The configuration is valid.
$ terraform plan -var="backend_image=...:dee0826c9e1121e348192058acde841b6f933608" \
                  -var="frontend_image=...:1c77231e2e1833bada6dc1c0f4f622f1d32e3470" \
                  -var="ai_image=...:unused"
Plan: 5 to add, 0 to change, 0 to destroy.
```

Full plan text: `CLOUD_04_TERRAFORM_PLAN_OUTPUT.txt`. Resources to add:

1. `module.backend_cloud_run.google_cloud_run_v2_service.this` (`pfi-backend-dev`)
2. `module.backend_cloud_run.google_cloud_run_v2_service_iam_member.public[0]` (`allUsers` → `run.invoker`)
3. `module.frontend_cloud_run.google_cloud_run_v2_service.this` (`pfi-frontend-dev`)
4. `module.frontend_cloud_run.google_cloud_run_v2_service_iam_member.public[0]`
5. `module.iam.google_service_account.created["frontend_runtime"]` (`pfi-frontend-runtime-dev`)

**Hard gate check: passed.** No destroy, no replace, no change to any preexisting resource
(`pfi-ai-module`, `pfi-containers`, the WIF pool/provider, the storage bucket). No Cloud SQL,
bucket, Artifact Registry, WIF, GPU, AI service, monitoring, or secrets resource appears.

## Smoke tests (to run once authorized and applied)

Backend:
- `GET /api/system/health` → 200 `{"status":"ok"}`
- `GET /api/studies` (or similar) unauthenticated → 401/403
- `POST /api/auth/demo-doctor` → JWT, then an authenticated call succeeds

Frontend:
- `GET /` → 200
- `GET /env.js` → 200, `API_BASE_URL` equals the Backend DEV URL
- Browser load: no fatal console error

No full product E2E this stage.

## Rollback strategy

Cloud Run keeps every revision. Rollback = shift 100% traffic back to the previous revision
(`gcloud run services update-traffic --to-revisions=<previous-revision>=100`), or re-`apply` the
same `.tfplan`/image reference. Demonstrated only after the first deploy is stable (not by
artificially breaking a working revision) — see `CLOUD_04_CHANGE_PLAN.md` for the exact procedure
and, once executed, its recorded timing/result.

## What CLOUD-04 explicitly does NOT do

Cloud SQL, real AI deploy, Qwen/GPU, Secret Manager, monitoring alert policies, a DEMO environment,
DNS/custom domain, `main` merge, `:latest` images, image rebuild.
