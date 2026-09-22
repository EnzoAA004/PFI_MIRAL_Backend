# Final Cloud Deployment Readiness Audit

Status: FINAL_CLOUD_DEPLOYMENT_READINESS_MINI_AUDIT

Date: 2026-09-22

Scope: readiness audit only. No deployment, no GCP mutation, no spend, no code change, no commit.

Evidence baseline:

- Backend main: `33081f7cc3702afe4db04a2b63de4762408b5d50`
- Frontend main: `ff191e1195a4e6bf06312ae8e266d37894c5b7b1`
- AI main: `22b12e04ce37f5c9ba6a02f356ac2022390bf06e`
- Product source of truth: `docs/final/FINAL_PRODUCT_TECHNICAL_SOURCE_OF_TRUTH.md`
- Cloud docs reviewed: `CLOUD_TARGET_ARCHITECTURE.md`, `CLOUD_ROADMAP.md`, `CLOUD_05_MANAGED_PERSISTENCE.md`, `CLOUD_05_STORAGE_DESIGN.md`, `CLOUD_08_DEPLOYMENT_RUNBOOK.md`, `CLOUD_03_COST_CHECK.md`, `CLOUD_01_COST_CHECK.md`
- Runtime evidence reviewed in Backend/AI repos: Backend persistence/storage code, Docker compose profiles, AI Dockerfile, AI input/asset registries, AI runtime supported formats, checkpoint inventory.

## Executive Result

`CLOUD_READY: PARTIAL`

The project has a real Cloud foundation: Artifact Registry/WIF plus Backend and Frontend Cloud Run DEV are deployed and validated by the existing Cloud documentation and the final source of truth.

The environment is not yet a complete, measurable Cloud product environment because durable Cloud persistence is not wired end to end. Cloud SQL, Secret Manager, Cloud Storage integration for medical inputs/AI outputs, and private AI Cloud Run are not deployed. Current local Docker profiles can persist some state through named volumes, but that does not carry over to Cloud Run, where writable container filesystem state must be treated as ephemeral.

## Current Deployed Resources

Validated as deployed:

- Artifact Registry / WIF for CI image publishing.
- Backend Cloud Run DEV.
- Frontend Cloud Run DEV.

Explicitly not deployed:

- Private AI Cloud Run service for the current AI Module.
- Cloud SQL PostgreSQL.
- Secret Manager secrets/values for the persisted DEV environment.
- Product Cloud Storage integration for uploads, DICOM series, AI outputs, reports, or durable assets.
- DEMO environment.
- Real live monitoring/alerting resources, where docs describe design/readiness rather than applied resources.

Important nuance: an older/pre-existing `pfi-ai-module` Cloud Run service is mentioned by Cloud docs as existing/untouched. It is not evidence that the current AI repo at `22b12e04ce37f5c9ba6a02f356ac2022390bf06e` is deployed as the private AI Cloud Run target.

## DICOM Persistence Status

`DICOM_PERSISTENCE_STATUS: PARTIAL_LOCAL_ONLY / NOT_CLOUD_COMPLETE`

What is implemented:

- AI input registration supports DICOM files and DICOM series.
- AI `input_registry.py` writes a disk-backed `registry.json` next to uploaded inputs.
- `register_series_files` writes normalized/deidentified `.dcm` files under `PFI_UPLOAD_DIR`.
- Backend stores study/input metadata and can persist domain state when running in Postgres mode.
- Local registry compose (`compose.yml`) mounts both `ai-uploads:/app/uploads` and `ai-outputs:/app/outputs`, so DICOM upload files and AI outputs can survive AI container replacement as long as those Docker volumes are preserved.

What is not complete for Cloud:

- Cloud Run local filesystem is ephemeral. A file saved under `PFI_UPLOAD_DIR` inside an AI container must not be considered durable.
- Backend stores references and metadata, not raw DICOM blobs.
- No application path currently writes deidentified DICOM series to Cloud Storage.
- No Cloud Storage object key is persisted in Backend as the durable input source of truth.
- Final E2E compose persists AI outputs via `pfi-final-e2e-ai-outputs`, but it does not set/mount `PFI_UPLOAD_DIR`; therefore it is not proof of durable upload persistence across full AI container recreation for uploaded DICOM inputs.

Cloud requirement:

- Store deidentified DICOM inputs/series in Cloud Storage.
- Store input metadata and object references in Cloud SQL.
- Make AI input resolution rehydrate from Cloud SQL + Cloud Storage, not from local process memory or local container disk.

## AI Artifact Persistence Status

`AI_ARTIFACT_PERSISTENCE_STATUS: PARTIAL_LOCAL_ONLY / EPHEMERAL_IN_CLOUD`

Current behavior:

- AI generated outputs are written under `PFI_OUTPUT_DIR`.
- Local compose mounts `PFI_OUTPUT_DIR` to a named Docker volume.
- `asset_registry.py` records run assets in an in-memory registry keyed by run id, plane, and asset name.
- Only the `lumbar-3d-mesh.json` workspace asset has a rehydration path from `PFI_OUTPUT_DIR/multiplanar_3d`.
- Most plane assets can 404 after an AI process/container restart because their metadata registry is not durable, even if files still exist.
- Backend has `PostgresRunAssetContentStorage` for small public derived assets in Postgres bytea, constrained by size and content validation. This helps selected PNG/mesh payloads once Backend runs in Postgres mode, but it is not a general binary store for raw DICOM, checkpoints, large masks, large outputs, or all AI browsing artifacts.

Minimum Cloud-grade solution:

- Use Cloud Storage for binary artifacts.
- Use Cloud SQL for artifact metadata, object keys, checksums, content type, visibility, plane, run id, and retention class.
- Rehydrate AI/Backend asset responses from Cloud SQL + Cloud Storage after AI restarts, Cloud Run scale-to-zero, or revision replacement.

Acceptable short-term local improvement:

- Add broader filesystem rehydration from `PFI_OUTPUT_DIR`.

But that is not sufficient for Cloud Run because the filesystem itself is not durable.

## MHA / MHD Usage

`MHA_USAGE: SUPPORTED_RUNTIME_INPUT / SECONDARY_PRODUCT_PERSISTENCE_CASE`

Evidence:

- AI runtime supports `.mha` and `.mhd` through `SUPPORTED_EXTENSIONS`.
- Existing AI docs and tests use MHA fixtures, including sagittal SPIDER evidence.
- The runtime also supports `.npy`, common image formats, `.dcm`, and `.ima`.
- Product DICOM ingest is implemented separately through multipart upload/server-side registration.

Cloud implication:

- MHA/MHD should use the same object-storage design as DICOM when uploaded through product flows.
- If MHA/MHD remain research fixtures only, they do not require a separate product retention class.
- Product runtime support does not equal Cloud persistence until uploaded MHA/MHD objects are written to Cloud Storage and referenced from Cloud SQL.

NIfTI note:

- `.nii` / `.nii.gz` are not part of the product runtime `SUPPORTED_EXTENSIONS` reviewed for the current AI main. They should not drive Cloud storage scope unless a future product path explicitly adds them.

## Checkpoint Readiness

`CHECKPOINT_READINESS: PARTIAL`

Current product-enabled checkpoints:

- `models/final/sagittal_spider_multiclass_final_best.pt` - 1,980,459 bytes.
- `models/final/axial_t2_alkafri_final_v2_candidate.pt` - 1,980,811 bytes.
- Associated manifests/model cards/preprocessing metadata are present under `models/final`.
- AI Dockerfile copies `models/final` into `/app/models/final`.

Therefore, sagittal and axial baseline checkpoints are ready for image-baked deployment.

Not ready / not product-enabled:

- Subarticular checkpoint is not present in repo/image; `models/subarticular` contains documentation only, and runtime integration depends on `PFI_SUBARTICULAR_CHECKPOINT_PATH`.
- P10.7 / 67B2 class work remains separate from product availability. The final source of truth keeps `67B2_RUNTIME_PARITY_NOT_PROVEN` and `productEnabled=false`.
- Runtime parity and Cloud packaging for these later checkpoints are not proven.

Image-size implication:

- Existing Cloud cost docs measured the AI image at about 2.12 GB. The two product checkpoints together are about 4 MB, so image size is dominated by Python/runtime/dependency layers, not by current final checkpoints.

## AI Cloud Run Readiness

`AI_CLOUD_RUN_READINESS: PARTIAL / NOT_DEPLOYED`

Ready pieces:

- AI Dockerfile exposes `PORT`, runs Uvicorn, and defines `/health` through a Docker healthcheck.
- CPU-only runtime is aligned with Cloud Run for product mock/demo inference.
- Backend has Google ID Token authorization code for private AI calls.
- Backend startup validation can fail closed when `PFI_AI_PRIVATE_REQUIRED=true` but `PFI_AI_AUTH_MODE=google-id-token` or a valid audience is missing.

Missing pieces:

- Current AI image has not been deployed as a private Cloud Run AI service.
- Terraform AI service module exists but is disabled by configuration in the documented state.
- Backend private-AI auth has not been exercised against a real private Cloud Run AI service.
- AI writes uploads/outputs to local paths unless object storage is implemented.
- Resource sizing/cold-start/timeout behavior has not been measured in a real Cloud Run AI deployment.
- The current plan must choose whether final checkpoints stay image-baked or are loaded from Cloud Storage/release URIs; either is viable for current small checkpoints, but it must be explicit before deployment.

## Database Readiness

`DB_READINESS: READY_FOR_DEPLOYMENT_PLAN / NOT_DEPLOYED`

Ready pieces:

- Backend supports `pfi.persistence.mode=postgres`.
- Migrations cover core domain state: studies, input resources, runs, artifacts, corrections, patient association, AI analysis results, evidence regions, chat, feedback, and audit events.
- Cloud docs define Cloud SQL PostgreSQL 15, Enterprise, `db-f1-micro`, `us-central1`, zonal, 10 GB.
- Docs report local validation of 17 migrations against clean Postgres with the already-published Backend image.

Missing pieces:

- No Cloud SQL instance exists for the final Cloud environment.
- Backend Cloud Run DEV remains memory-mode according to the managed-persistence docs until Cloud SQL/Secret Manager are applied.
- No production-like restart persistence test exists in Cloud against Backend Cloud Run + Cloud SQL.

Database storage boundary:

- Store durable metadata, review/correction state, measurements, annotations, references, auth/domain records, and audit events in Cloud SQL.
- Do not store raw DICOM, full masks, checkpoints, or large output trees in Cloud SQL.
- Existing small-asset bytea storage can be kept for bounded public derived assets, but object storage is the right target for unbounded or medically heavy binary payloads.

## Storage Readiness

`STORAGE_READINESS: DESIGN_ONLY / NOT_WIRED`

Current state:

- Existing bucket `pfi-rm-lumbar-artifacts-2026-ef` is documented as external/existing.
- No new bucket was created in the reviewed Cloud stage.
- Existing docs state the bucket is not wired to Backend, Frontend, or AI app code paths as a read/write target.
- No bucket IAM grant was applied for product runtime service accounts in the reviewed state.

Recommended object layout:

```text
gs://pfi-rm-lumbar-artifacts-2026-ef/dev/
  studies/{studyId}/inputs/{inputId}/dicom/...
  studies/{studyId}/inputs/{inputId}/source.{mha|mhd|npy|png|jpg|dcm|zip}
  runs/{runId}/{plane}/public/input.png
  runs/{runId}/{plane}/public/overlay.png
  runs/{runId}/{plane}/public/mask-preview.png
  runs/{runId}/{plane}/slices/slice-000.png
  runs/{runId}/{plane}/internal/mask.npy
  runs/{runId}/{plane}/internal/confidence.npy
  runs/{runId}/reports/report.json
  models/final/{modelVersion}/...
  tmp/{traceId}/...
```

Persistence classes:

- Must persist: deidentified product inputs, input registry metadata, run metadata, object references, selected public reviewer assets, reports/exports, model manifests, and exact model version identifiers.
- Should persist when audit/review requires exact reproduction: raw masks, confidence arrays, slice previews, generated JSON payloads.
- Can regenerate if explicitly documented: overlays/previews derived from persisted source image + model version + parameters.
- Temporary only: upload staging zips, intermediate scratch files, incomplete run workspaces, and unserved transient arrays.

## Secret Readiness

`SECRET_READINESS: PLAN_ONLY / NOT_DEPLOYED`

Required for persisted DEV:

- `DATABASE_URL` / DB credentials for Backend.
- `PFI_AUTH_JWT_SECRET`.

Required depending on feature enablement:

- LLM provider keys only if real external LLM integration is enabled.
- Model download token only if runtime model release download is used from protected storage.

Not secrets:

- `PFI_AI_SERVICE_URL`.
- `PFI_AI_SERVICE_AUDIENCE`.
- `PFI_AI_AUTH_MODE`.
- `PFI_AI_PRIVATE_REQUIRED`.
- Cloud Storage bucket names and object prefixes.

These are deployment configuration values. Service-to-service auth should use Cloud Run service accounts/IAM and Google ID tokens, not shared static credentials.

## Blockers

### P0

1. Cloud SQL and Secret Manager are not deployed; Backend Cloud Run DEV is not yet a durable persisted environment.
2. No Cloud Storage integration exists for DICOM uploads, uploaded MHA/MHD inputs, AI outputs, or durable viewer/report assets.
3. Private current AI Cloud Run is not deployed; Backend Google ID Token auth has not been validated against a live private AI service.

### P1

1. AI asset registry is mostly in-memory and can lose run asset mappings after AI restart/scale-to-zero.
2. DICOM input persistence relies on local disk/volumes in non-Cloud profiles; Cloud Run needs object storage rehydration.
3. AI image/resource sizing/cold start/inference latency are not measured in a real Cloud Run deployment.
4. Later checkpoints/features such as subarticular, P10.7, and 67B2 are not product-enabled Cloud runtime artifacts.
5. The exact binary ownership boundary between Backend Postgres bytea storage and Cloud Storage object references must be finalized before deployment.

### P2

1. MHA/MHD retention policy should be made explicit if browser/product uploads use them.
2. Monitoring/alerting is designed but not live.
3. Cost labels, billing export, and per-run cost telemetry are not yet in place.
4. Storage lifecycle and Artifact Registry retention policies should be applied before repeated AI image pushes.

## Exact Deployment Order

No step below has been executed by this audit.

1. Freeze SHAs and image digests for Backend, Frontend, and AI.
2. Finalize Cloud Storage object model: prefixes, retention classes, content types, checksums, and object-key schema.
3. Add runtime IAM plan: Backend service account, AI service account, bucket object roles, Secret Manager access, Cloud SQL access, and Cloud Run invoker.
4. Create Secret Manager containers and write secret values out of band: DB URL/credentials and JWT secret first.
5. Create Cloud SQL DEV (`pfi-postgres-dev`) and database/user configuration per Cloud docs.
6. Deploy a Backend Cloud Run DEV revision with Postgres mode, Cloud SQL Auth Proxy sidecar, and Secret Manager env wiring.
7. Validate Backend restart persistence: studies, patients, patient-study associations, runs, review edits, and stored small assets.
8. Implement/wire Cloud Storage persistence for deidentified inputs and AI artifacts.
9. Add Cloud SQL metadata for storage object references and rehydration paths.
10. Publish or verify the current AI image in Artifact Registry; explicitly decide whether sagittal/axial checkpoints remain baked into the image or come from release URIs.
11. Deploy current AI Module as private Cloud Run with `min_instances=0`, bounded `max_instances`, explicit memory/CPU/timeout/concurrency, and service account.
12. Configure Backend private-AI auth: `PFI_AI_SERVICE_URL`, `PFI_AI_AUTH_MODE=google-id-token`, `PFI_AI_PRIVATE_REQUIRED=true`, and a valid audience.
13. Grant Backend runtime service account Cloud Run Invoker on the AI service.
14. Run Cloud E2E: browser -> Frontend Cloud Run -> Backend Cloud Run -> Cloud SQL -> private AI Cloud Run -> Cloud Storage.
15. Run restart/rehydration tests: Backend revision restart, AI revision restart, AI scale-to-zero/cold start, Cloud Run instance replacement, and asset URL retrieval after each restart.
16. Run DICOM persistence test: upload deidentified DICOM series, create study/run, restart Backend and AI, confirm the same study/input/run/assets remain retrievable.
17. Run cost/performance measurement pass and update cost docs with observed latency, request counts, CPU/memory, storage growth, and billing export data.
18. Only after DEV is measured, promote the same validated image digests to DEMO if separately authorized.

## Cost-Control Recommendations

- Keep DEV `min_instances=0` for Frontend, Backend, and AI unless a live demo window requires warm instances.
- Cap AI `max_instances` tightly at first; use low concurrency for AI until memory and latency are measured.
- Keep Backend/Frontend max instances low in DEV.
- Use Cloud SQL `db-f1-micro`, zonal, 10 GB for DEV as documented; acknowledge it is the main always-on monthly cost.
- Stop or tear down Cloud SQL outside active validation windows if preserving data is not required.
- Apply Cloud Storage lifecycle rules:
  - temp/staging: 1-7 days.
  - DEV generated outputs: 30-90 days unless tied to thesis evidence.
  - deidentified raw inputs: shortest retention compatible with reproducibility.
  - model releases: keep only approved/versioned releases.
- Apply Artifact Registry cleanup for repeated SHA-tagged AI images because the AI image is much larger than Backend/Frontend.
- Label all Cloud resources with environment, component, repo SHA, and owner/project.
- Export billing data and correlate by labels; do not rely on estimates once the full environment exists.
- Measure per-run: request count, inference duration, cold-start duration, CPU/memory peak, input/output bytes, object count/size, DB rows/storage, log volume, and error retries.

## Final Readiness Summary

The product is Cloud-foundation ready, but not complete-Cloud ready.

The missing core is not another general design pass; it is durable runtime wiring:

- Cloud SQL for Backend state.
- Secret Manager for real Backend secrets.
- Cloud Storage for deidentified inputs and AI artifacts.
- Rehydration metadata linking Backend/AI run records to Cloud Storage objects.
- Private Cloud Run deployment of the current AI Module.
- Restart/scale-to-zero E2E proving DICOM inputs and AI artifacts survive.
- Measured cost/performance after the above is live.

Until those are complete, the correct classification is:

`CLOUD_READY: PARTIAL`

`FINAL_CLOUD_DEPLOYMENT_READINESS_AUDIT_COMPLETE`
