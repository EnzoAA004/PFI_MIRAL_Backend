# Cloud Compatibility Matrix

Read-only audit output. No resource created.

| Component | Current behavior | Cloud-ready? | Required change | Risk | Cost impact |
|---|---|---|---|---|---|
| Backend port binding | `server.port=${PORT:8080}` | **Yes** | None | None | None |
| Backend persistence | Postgres via `DATABASE_URL`; `memory` mode fails closed outside dev | **Yes** | Point `DATABASE_URL` at Cloud SQL (or a dev Postgres) | Low | Cloud SQL cost (see cost model) |
| Backend statelessness | No filesystem writes found in app code | **Yes** | None | None | None |
| Backend health endpoint | None (no Actuator dependency) | Partial | Add `spring-boot-starter-actuator`, expose `/actuator/health` | Low | Negligible (no billable API) |
| Backend secrets | Plain env vars, insecure dev defaults | **No** | Secret Manager for JWT secret, DB password | **High if shipped as-is** | Small (Secret Manager access calls) |
| Backend CORS | Env-var list, already includes one external (Vercel) origin | **Yes** | Add the real Cloud Run Frontend URL | Low | None |
| Backend AI/LLM URLs | Env-var driven, default `localhost` | **Yes** (mechanism) | Set real internal URLs at deploy time | Low | None |
| Frontend port binding | nginx hardcodes `listen 80`; Cloud Run injects `PORT` | **No** | Template `nginx.conf`'s `listen` from `$PORT`, same pattern as `env.js` | Low | None |
| Frontend runtime config | `BACKEND_URL` → `env.js`, generated at container start | **Yes** | None — already build-once/deploy-anywhere | None | None |
| Frontend secrets/DB/AI | None present, by design | **Yes** | None | None | None |
| AI service port binding | `${PORT:-8000}` already used by uvicorn | **Yes** | None | None | None |
| AI service health | `HEALTHCHECK` already defined in Dockerfile | **Yes** | Wire the same `/health` into Cloud Run's own probe config | Low | None |
| AI service model artifacts (P10.7/sagittal/axial "final") | Baked into image (`COPY models/final`) | Partial | Fine for now; revisit if models grow (image size/cost) | Low | Artifact Registry storage |
| AI service model artifacts (EXT-03 subarticular checkpoint) | **Not copied into the image at all** | **No** | Add to Dockerfile `COPY`, or externalize to Cloud Storage + startup fetch | Medium (feature silently 503s in prod today) | Small storage + egress if fetched from GCS |
| AI service output persistence | Written to container-local `outputs/` | **No** | Decide: disposable (no change) vs. persist to Cloud Storage | Medium (data loss on every new revision if outputs matter) | Storage cost if persisted |
| AI service compute profile | CPU-only PyTorch, no CUDA | **Yes for CPU workloads**; **No for GPU-required** (EXT-03 Grad-CAM is CPU-fine; a real Qwen 7B is not) | See `ADR-005` (AI compute strategy) | Medium for LLM specifically | See cost model |
| Windows/CRLF assumptions | Git CRLF warnings only, no functional bug found | **Yes** | Optional: add `.gitattributes` | None | None |
| CI (all three repos) | GitHub Actions already exist; Frontend already builds+pushes to GHCR on `main` | Partial | Add Docker build validation to Backend/AI CI; none currently push to Artifact Registry | Low | GHCR is free for public/reasonable private use; Artifact Registry billed by storage |
| GCP project state | Artifact Registry, one live Cloud Run service, one bucket, 4 service accounts already exist | N/A (pre-existing) | Inventory before any new resource; do not duplicate | **Verify what `pfi-ai-module` currently runs before building CLOUD-04** | Ongoing (see cost model) |
