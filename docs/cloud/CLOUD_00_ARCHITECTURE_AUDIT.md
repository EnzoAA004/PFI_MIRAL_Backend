# CLOUD-00 — Current Architecture & Cost Audit

**Read-only. USD spent this stage: $0.** No `terraform apply`, no `gcloud ... create/update/delete`,
no API enabled, no IAM change, no deployment. Every finding below comes from reading source files
and running only list/describe (`gcloud ... list`, `gcloud ... describe`) commands.

## 0. Important finding first: this GCP project is not empty

Before anything else — the audited project **already has real resources**, created outside this
session (no memory of them existed beforehand; everything here was verified live via CLI, never
assumed):

| Resource | Value |
|---|---|
| Project | `pfi-asplanatti-fabrello-v1` (number `810538429027`, created 2026-07-18) |
| Active account | `enzoandreaasplanatti@gmail.com` |
| Artifact Registry | `pfi-containers` (Docker, `us-central1`, 413.6 MB, created 2026-08-10) |
| Cloud Run service | **`pfi-ai-module`**, `us-central1`, deployed 2026-08-11, live at `https://pfi-ai-module-810538429027.us-central1.run.app` |
| Cloud Storage bucket | `pfi-rm-lumbar-artifacts-2026-ef` (`US-CENTRAL1`, created 2026-07-19, uniform bucket-level access, public access prevention enforced) |
| Service accounts | `pfi-training-vm@...`, `pfi-ai-runtime@...`, `pfi-backend-runtime@...`, plus the default Compute Engine SA |
| Compute Engine instances | **0** — verified via `gcloud compute instances list` (no VM billing risk right now) |
| Cloud SQL | Admin API **not enabled** — no instance exists, none was created |

**Cost-relevant detail on `pfi-ai-module`**: 4 vCPU / 2 GiB, `containerConcurrency: 1`,
`run.googleapis.com/maxScale=1`, **no `minScale` annotation** → defaults to scale-to-zero. This
means it should cost close to nothing while idle (Cloud Run gen2 without minimum instances bills
per-request, not per-hour), but it is a **live, publicly-ingress (`ingress=all`) service** that
this audit did not create and did not touch. Recommend confirming with whoever deployed it (likely
tied to the "PFI Training VM"/model-training work, given the service-account names) whether it is
still needed, since CLOUD-00 did not verify what code it is running or whether that code is current.

This changes the framing of the rest of this roadmap: CLOUD-01 through CLOUD-04 are not "create
from zero" — they are "adopt/extend what already exists, carefully, and only the pieces still
missing" (Cloud Run for Backend/Frontend, Cloud SQL, Secret Manager, CI/CD wiring).

## 1. Repos audited

- `PFI_MVPTest_Enzo_Backend` (Spring Boot, Java 21 at runtime/build, Maven)
- `PFI_MVPTest_Enzo_Frontend` (React/Vite/TypeScript, Node 24 build / nginx 1.27 runtime)
- `PFI_MVPTest_Enzo_AImodule` (`ai_service/`, Python 3.12, FastAPI + PyTorch CPU + timm)

All three already have a working, distinct Dockerfile and an existing `.github/workflows/*-ci.yml`
(details in `CLOUD_CICD_DESIGN.md`).

## 2. Per-component findings

### Backend

- `Dockerfile`: multi-stage (`maven:3.9-eclipse-temurin-21` build → `eclipse-temurin:21-jre-alpine`
  runtime). `EXPOSE 8080`; **`application.properties` already reads `server.port=${PORT:8080}`** —
  genuinely Cloud-Run-ready on the port dimension, no change needed.
- No Spring Boot **Actuator** dependency in `pom.xml` — no `/actuator/health` endpoint exists today.
  Cloud Run doesn't strictly require one, but CLOUD-00M (observability) and any future load-balancer
  health check would benefit from adding `spring-boot-starter-actuator` (near-zero cost, standard
  practice) — flagged as a required change, not yet made.
- **Statelessness**: no filesystem-write code paths found in `src/main/java` (grepped for
  `Paths.get`/`Files.write`/`new File(`) — measurements, evidence geometry, feedback, everything
  audited this session is stored as Postgres rows (including JSONB geometry), never local files.
  This is a strong Cloud Run fit (Cloud Run containers are ephemeral/stateless by design).
- `PFI_PERSISTENCE_MODE`: `memory` (Dockerfile default, dev-only) vs `postgres` (required — a
  startup validator, `SecurityStartupValidator`, **fails boot outright** if a "production" profile
  runs with anything other than `postgres`). Real fail-closed guard already in place.
- CORS (`pfi.cors.allowed-origins`) already defaults to include
  `https://pfi-mvp-test-enzo-frontend.vercel.app` — evidence of a past/parallel Vercel deployment
  attempt for the Frontend, unrelated to this GCP project. Whatever Cloud Run Frontend URL is
  chosen will need to be added here (env var override, not a code change).
- Secrets currently read via plain env vars with insecure defaults for local dev
  (`PFI_AUTH_JWT_SECRET` defaults to `pfi-demo-change-me-2026`, demo auth mode defaults `false`).
  **Must never reach a real deployment with defaults** — Secret Manager integration is a hard
  requirement before any DEMO deploy (see `CLOUD_SECURITY_MODEL.md`).
- `PFI_AI_SERVICE_URL` / `PFI_LLM_BASE_URL`: both already externalized env vars, defaulting to
  `localhost` — need real internal URLs once AI/LLM services have their own Cloud Run/Compute
  addresses.
- Upload limits already bounded (`200MB` multipart, `5MB` asset-storage max) — no unbounded-upload
  risk found.

### Frontend

- `Dockerfile`: multi-stage (`node:24-alpine` build → `nginx:1.27-alpine` runtime). Build-time
  `VITE_API_BASE_URL` ARG exists but is **deliberately not used at runtime** — `docker/entrypoint.sh`
  regenerates `/usr/share/nginx/html/env.js` from the `BACKEND_URL` env var on every container
  start, and the compiled app reads `window.__PFI_CONFIG__.API_BASE_URL` at runtime instead of a
  baked-in constant. This is exactly the "build once, deploy anywhere" pattern CLOUD-00O's
  "no rebuild between DEV and DEMO" requirement needs — already implemented, no change required.
- **Cloud Run port mismatch, real gap**: `docker/nginx.conf` hardcodes `listen 80`. Cloud Run
  injects `PORT` (default `8080`) and expects the container to listen on it. Two ways to close
  this, neither implemented yet: (a) template `nginx.conf`'s `listen` directive from `$PORT` in
  `entrypoint.sh` the same way `env.js` is already templated, or (b) explicitly set the Cloud Run
  service's container port to `80` at deploy time. (a) is more portable and is the recommended fix
  for CLOUD-04.
- No secrets, no DB connection, no direct AI call from the Frontend container — confirms the
  "Frontend: no DB, no Secret Manager, no AI directo" least-privilege target in
  `CLOUD_SECURITY_MODEL.md` needs no code change, only IAM restriction at deploy time.

### AI service (`ai_service/`)

- `Dockerfile`: `python:3.12-slim`, installs CPU-only `torch` explicitly from PyTorch's own CPU
  wheel index (`--index-url https://download.pytorch.org/whl/cpu`) — deliberate, keeps the image
  smaller and avoids pulling CUDA libraries into a CPU-only runtime target. `EXPOSE 8000`, and the
  `CMD` already reads `${PORT:-8000}` for uvicorn — **also genuinely Cloud-Run-ready on the port
  dimension**.
- `HEALTHCHECK` already defined (`curl .../health`) — good Docker-native practice, translates
  directly to a Cloud Run startup/liveness probe if configured at deploy time.
- **Real gap found**: the Dockerfile only `COPY models/final /app/models/final` — it does **not**
  copy `models/subarticular/` (the EXT-03 Grad-CAM classifier's checkpoint directory, gitignored,
  48.6 MB locally). Any real deployment of EXT-03's `/degenerative-findings/subarticular/explain`
  endpoint would 503 in this image today (`CheckpointNotFoundError`) until the Dockerfile is
  updated to also copy that directory (or, better, until model artifacts are pulled from Cloud
  Storage/Artifact Registry at startup instead of baked into the image — see `ADR-005`).
- `PFI_MODEL_DIR`/`PFI_OUTPUT_DIR` are filesystem paths inside the container — `outputs/` is
  written to at runtime (agent reports, etc.). This is the one real **statefulness assumption**
  found across all three services: outputs currently live only inside the container's ephemeral
  filesystem, which Cloud Run resets on every new revision/instance. Needs Cloud Storage-backed
  output persistence before a real deployment, or an explicit decision that these outputs are
  disposable (needs a product decision, not assumed here).
- `timm` and `pillow` (needed for EXT-03's Grad-CAM/PNG encoding) are **already** in
  `requirements-ai-service.txt` — no new dependency, no Dockerfile change needed for that part.

## 3. CRLF / Windows-specific findings

Every commit this session on Windows produced `warning: LF will be replaced by CRLF` — cosmetic,
Git-normalizes-on-checkout behavior, not a functional bug (confirmed: Docker builds inside Linux
containers via WSL2/Docker Desktop this whole session, no CRLF-related runtime failures observed
in any of the ~10 E2E Docker cycles run across EXT-04B5/05/02/01/03). No `.gitattributes` enforcing
line endings exists in any of the three repos — worth adding (`* text=auto eol=lf`) as a small
hygiene improvement, not a blocker.

## 4. `localhost` dependencies inventory

| Var | Default | Used by |
|---|---|---|
| `PFI_AI_SERVICE_URL` | `http://localhost:8000` | Backend → AI |
| `PFI_LLM_BASE_URL` | `http://localhost:8001` | Backend → self-hosted LLM (EXT-06, blocked) |
| `BACKEND_URL` | `http://localhost:8080` | Frontend nginx entrypoint → Backend |
| `VITE_API_BASE_URL` | `http://localhost:8080` | Frontend build-time only (superseded by `BACKEND_URL` at runtime, see above) |
| `DATABASE_URL` | none (required) | Backend → Postgres |

All five are already env-var-driven — no hardcoded `localhost` found in application code, only in
default values. This is exactly the shape needed for multi-environment deployment.

## 5. Image size

Not measured with a real `docker build` this stage (out of scope for a $0, no-heavy-build audit
given this machine's memory pressure observed earlier this session). Qualitative estimate from
base images alone: Backend runtime base `eclipse-temurin:21-jre-alpine` (~80 MB) + a Spring Boot
fat jar (typically 60–120 MB for a project this size) ≈ 150–200 MB. Frontend runtime base
`nginx:1.27-alpine` (~40 MB) + built static assets (typically a few MB) ≈ 50 MB. AI service is the
outlier: `python:3.12-slim` (~150 MB) + CPU PyTorch (~200 MB+) + `timm`/`pillow`/other deps +
baked-in `models/final` checkpoints (unmeasured, likely tens to low hundreds of MB) — realistically
the largest of the three images, plausibly 600 MB–1.5 GB. Should be measured for real before
CLOUD-03 (Artifact Registry storage cost scales with image size × versions retained).

## Status

`CLOUD_00_ARCHITECTURE_AND_COST_AUDIT_READY` — see the other 7 documents in this directory for the
full compatibility matrix, cost model, target architecture, CI/CD/rollback/observability designs,
security model, and roadmap. No cloud resource was created, modified, or deleted by this audit.
