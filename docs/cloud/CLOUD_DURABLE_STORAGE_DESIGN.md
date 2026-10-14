# Cloud P0B -- Backend Durable Object Storage & Real Restart Evidence

Status: filesystem provider implemented and restart-verified for both the AI module and
the Backend; GCS provider code-ready, not deployed. Supersedes the "STORAGE_READINESS:
DESIGN_ONLY / NOT_WIRED" finding in `FINAL_CLOUD_DEPLOYMENT_READINESS_AUDIT.md` for the
Backend side, matching the AI module's durable asset persistence
(`docs/AI_DURABLE_ASSET_PERSISTENCE.md` in the AI repo, commits `b2526da`/`87eae58`).

## 1. Storage contract

`ObjectStorage` (`src/main/java/ar/edu/uade/pfi/backend/storage/ObjectStorage.java`):
`put`, `get`, `exists`, `stat`, `delete`, `providerName`. Deliberately the same shape as
the AI module's `ObjectStorage` (`ai_service/pfi_ai_service/object_storage.py`) so both
services can be restart-tested the same way and an operator configures them with the
same env var names.

Object keys never carry PHI -- only opaque studyId/runId/reportId/assetId values that
already exist as database primary keys (`ObjectKeys.java`):

```
studies/{studyId}/inputs/{assetId}/{filename}
studies/{studyId}/outputs/{runId}/{filename}
studies/{studyId}/reports/{reportId}/{filename}
```

`ObjectKeys` rejects blank segments and path traversal in `filename` (`..`, `/`, `\`)
before a key is ever built.

## 2. Ownership

- **AI module**: owns `runs/{runId}/{plane}/{assetName}` (unchanged, already durable) --
  segmentation overlays, masks, confidence maps, the per-run asset registry index.
- **Backend**: owns `studies/{studyId}/inputs/...` (raw uploaded DICOM ZIP, so it can be
  reprocessed without asking the client to upload again) and reserves
  `studies/{studyId}/outputs/...` / `studies/{studyId}/reports/...` for report PDFs/JSON
  once those are wired to durable storage (see Section 9).

Two independent object stores, not a shared bucket namespace: the Backend never reaches
into `runs/...` and the AI module never reaches into `studies/...`.

## 3. Filesystem provider

`FilesystemObjectStorage` (Backend) / `FilesystemObjectStorage` (AI module, pre-existing).
Backend implementation:

- Root resolved from `PFI_STORAGE_LOCAL_ROOT` (default `/app/storage/object_storage`),
  must be a Docker named volume (`backend-storage`), never the container's ephemeral
  layer.
- Atomic write: content is written to a `.tmp` sibling, then `Files.move` with
  `ATOMIC_MOVE` + `REPLACE_EXISTING`.
- A `.sha256` sidecar file (content type, size, checksum) travels next to every object.
- `get`/`stat` recompute SHA-256 and compare to the sidecar; a mismatch raises
  `ObjectStorageException` with "checksum ... no coincide" rather than returning
  truncated or bit-rotted bytes.
- Object keys are resolved under the root and rejected (`ObjectStorageException`) if
  they would resolve outside it.

## 4. GCS provider (code-ready, not deployed)

`GcsObjectStorage` uses `StorageOptions.getDefaultInstance().getService()` --
Application Default Credentials only, no service-account JSON key file, matching the
posture the Backend already uses for Google ID-token auth to the AI module
(`google-auth-library-oauth2-http`).

Fails closed at construction: a blank `PFI_STORAGE_GCS_BUCKET` throws immediately.
`ObjectStorageConfig.build()` has no fallback branch -- an unrecognized
`PFI_STORAGE_PROVIDER` value fails application startup rather than silently defaulting
to filesystem. **No GCS API call was made in this work**: `GcsObjectStorageTest` only
exercises the fail-closed constructor guard, never a real bucket.

## 5. Configuration

| Env var | Default | Notes |
|---|---|---|
| `PFI_STORAGE_PROVIDER` | `filesystem` | `filesystem` or `gcs`; anything else fails startup |
| `PFI_STORAGE_LOCAL_ROOT` | `/app/storage/object_storage` | filesystem provider root |
| `PFI_STORAGE_GCS_BUCKET` | *(empty)* | required when provider=gcs |

Same names as the AI module's `PFI_STORAGE_PROVIDER` / `PFI_STORAGE_LOCAL_ROOT` /
`PFI_STORAGE_GCS_BUCKET` (`docs/CLOUD_ENVIRONMENT_VARIABLES.md` in the AI repo).

## 6. Metadata model

`domain_durable_object_assets` (migration `V20260922_023_durable_object_assets.sql`):
one row per persisted binary, discriminated by `artifact_type` (`study_input`,
`run_output`, `report`) rather than three separate tables, since the row shape is
identical across all three. Columns: `asset_id`, `study_id`, `run_id`, `report_id`,
`object_key` (unique), `content_type`, `size_bytes`, `sha256`, `artifact_type`,
`storage_provider`, `original_filename`, `created_at`. No binary content lives in
Postgres -- only the object key that resolves it in the configured provider. This is
additive to the existing `domain_run_artifacts` / `domain_run_asset_payloads` tables
(small derived PNG/mesh-JSON assets, capped at 5MB, unaffected by this work) rather than
a replacement of them.

## 7. DICOM durable flow

`AiBackendService.uploadStudy`: after the existing ZIP-signature/size/caseId validation,
the raw bytes are read once and persisted via `ObjectStorageService` under
`studies/{studyId}/inputs/{assetId}/study.zip` **before** the response is returned to the
caller, then forwarded to the AI module exactly as before. Durable persistence is
best-effort: a storage failure is audited (`upload.study.durable_persist_failed`) but
does not fail the upload the clinician is waiting on, since the AI module has already
accepted and processed the study.

`AiBackendService.reprocessStudy(studyId, caseId)` (new: `POST
/api/ai/studies/{studyId}/reprocess`) reads the most recent durable `study_input` row
for a `studyId`, fetches the bytes from `ObjectStorage`, wraps them in a
`ByteArrayMultipartFile`, and resends them to the AI module exactly as a fresh upload
would -- proving the raw study is reusable from durable storage alone, with no new
client upload. 404 if nothing was ever persisted for that `studyId`; 410 if the
durable object was deleted out from under the metadata row.

Objects the Backend has never written to (any other studyId/plane/asset combination)
continue to 404 through the existing asset-lookup path -- this change adds a new,
additive persistence side-effect and a new endpoint; it does not alter any existing
response shape.

## 8. Restart evidence

### AI module -- real container restart (independent verification)

Built `pfi-ai-module:p0b-restart-test` from AI repo HEAD `87eae58` (durable asset
persistence commits already on this branch). Ran standalone with `PFI_OUTPUT_DIR=/app/outputs`
bound to a named volume (`pfi-ai-p0b-outputs`):

1. `POST /pipeline/run` (`inferenceMode: real_baseline`) against the repo's own
   `ai_service/tests/fixtures/real_baseline/sagittal_sample_input.npy` fixture --
   `runId=857a685f1c1d6d43`. Response includes `objectKey`/`storageProvider`/`sha256`
   per asset, confirming the durable-storage write path is live (not the in-memory
   registry alone).
2. `GET /assets/857a685f1c1d6d43/sagittal/overlay.png` -> 200, 67919 bytes,
   sha256 `1ad7a4ce...db061`.
3. `docker restart pfi-ai-p0b-test` (real process kill + restart, same container).
4. Same request -> 200, **byte-identical** (same sha256). In-memory asset registry was
   wiped by the process restart; the response came from rehydration against the durable
   volume (`load_asset_index_record` in `asset_registry.py`).
5. `docker rm -f` + `docker run` on the **same named volume** (the compose
   down/up equivalent for a single service) -> same request -> 200, still
   byte-identical.

One real defect surfaced and fixed during this evidence run, not in application code:
the first attempt passed `PFI_OUTPUT_DIR=/app/outputs` through a Git-Bash shell that
silently rewrote the value to a Windows path (`C:/Program Files/Git/app/outputs`)
before it reached `docker run`. The container still started and served assets (written
into its ephemeral layer instead of the volume), which is why the *first* restart
attempt looked like it passed. Re-run with `MSYS_NO_PATHCONV=1` confirmed the object
actually lives at `/app/outputs/object_storage/runs/857a685f1c1d6d43/sagittal/overlay.png`
on the named volume, and only then does the restart/recreate evidence above hold. This
is a tooling footgun of driving `docker run` from Git Bash on Windows, not a product
bug -- worth calling out because it would have produced false-positive restart evidence
if the first pass had been trusted.

### Backend + Postgres -- real container restart

Full stack (`postgres`, `ai-module`, `backend`) built and run from source via
`compose.local.yml` (project `pfip0b`), all three from this branch's HEAD.

1. Minted a demo-doctor JWT (`POST /api/auth/demo-doctor`).
2. Generated a real (synthetic, non-PHI, pydicom-built -- two series, sagittal + axial,
   same construction pattern as the AI repo's own
   `ai_service/tests/test_study_zip_keeps_all_series.py`) DICOM ZIP and uploaded it via
   `POST /api/ai/studies` -> 200, `studyId=b296b7f5c56b4ab5bcc97dff7f495ba2`, both series
   parsed correctly by the AI module.
3. Confirmed a `domain_durable_object_assets` row (`artifact_type=study_input`,
   `storage_provider=filesystem`, `size_bytes=36214`) and the object file on the
   `backend-storage` volume at
   `studies/b296b7f5c56b4ab5bcc97dff7f495ba2/inputs/4302fe38.../study.zip`.
4. `POST /api/ai/studies/b296b7f5c56b4ab5bcc97dff7f495ba2/reprocess` (no file in the
   request) -> 200, both series re-parsed identically from the durable copy.
5. `docker restart pfi-backend` (real process kill + restart). Backend became healthy
   again; the Postgres row was unchanged (separate container, untouched); reprocess ->
   200 again, same durable object, no re-upload.
6. `docker compose down` (no `-v`) then `docker compose up -d postgres ai-module
   backend`: all four named volumes (`postgres-data`, `backend-storage`, `ai-outputs`,
   `ai-uploads`) survived the teardown; Postgres row and the volume file were both still
   present after recreation; reprocess -> 200 a third time.
7. Regression: a non-ZIP payload to `POST /api/ai/studies` still returns 400
   (`El archivo del estudio no parece un ZIP valido`), never 500/502 -- the Backend's own
   ZIP-signature gate runs before any durable-storage or AI-module call. The AI module's
   own deeper `UNSUPPORTED_INPUT` 422 for a structurally-invalid DICOM (commit `dc1fa10`
   on the AI branch) was not re-exercised live in this pass; it is unchanged code,
   already covered by the AI module's own passing test suite.

| Resource | Before | After AI/Backend restart | After compose down/up | Result |
|---|---|---|---|---|
| Study (AI-side, per upload) | created (`b296b7f5c56b4ab5bcc97dff7f495ba2`) | n/a -- AI mints a fresh studyId per ZIP ingest, by design (stateless re-ingestion); the reused **input bytes** are what persists, not an AI-side "study" record | n/a, same as previous column | PASS (bytes reused, not an AI record) |
| Input asset (`domain_durable_object_assets` row) | 1 row, `study_input` | unchanged after `docker restart pfi-backend` | unchanged after `down`/`up` | PASS |
| Input asset (object on `backend-storage` volume) | present, 36214 bytes | present, same path | present, same path | PASS |
| Reprocess without re-upload | 200, both series parsed | 200, both series parsed | 200, both series parsed | PASS |
| AI run + asset (`overlay.png`, independent verification, standalone AI container) | 200, sha256 `1ad7a4ce...db061` | 200, identical sha256 after `docker restart` | 200, identical sha256 after container remove+recreate on same volume | PASS |
| Report/PDF | not built this pass | n/a | n/a | see Section 9 |

## 9. Report / PDF persistence

Not built in this pass. Classification for a follow-up:

- **MUST_PERSIST**: the structured report JSON a clinician signed off on (it is the
  legal record of what was reviewed, and it can diverge from what a re-run would
  produce once measurements are edited).
- **SHOULD_PERSIST**: the rendered report PDF (regenerable byte-for-byte from the JSON
  today, but persisting the exact bytes a clinician downloaded avoids any drift if the
  renderer changes later).
- **CAN_REGENERATE**: intermediate rendering artifacts (fonts, embedded chart images)
  that have no independent clinical meaning.

`ObjectKeys.studyReport(studyId, reportId, filename)` already exists for this; wiring it
into the report export path is follow-up work, not part of this change.

## 10. MHA/MHD

No non-synthetic MHA/MHD fixture exists in either repo. Per the P0B instructions, no
fixture was invented. Status:

- `RUNTIME_SUPPORTED`: yes -- `.mha`/`.mhd` are in the Backend's
  `ALLOWED_INPUT_EXTENSIONS` and the AI module's SimpleITK-backed input pipeline reads
  them.
- `STORAGE_CONTRACT_SUPPORTED`: yes -- the durable flow in Section 7 is format-agnostic;
  it persists whatever bytes were uploaded, keyed by studyId, regardless of extension.
- `E2E_NOT_EXERCISED_NO_FIXTURE`: an actual MHA/MHD durable-persist-then-restart run was
  not exercised in this pass.

## 11. Invalid DICOM regression

Unchanged and still covered by the pre-existing Backend/AI test suites: the Backend's
`validateStudyUpload` rejects a non-ZIP-signature payload with 400 before ever calling
the AI module (`AiBackendServiceInputValidationTest.uploadStudyRejectsNonZipSignature...`),
and the AI module's own DICOM-parsing rejections surface as a structured
`UNSUPPORTED_INPUT` 422 (`dc1fa10` on the AI branch, pre-existing). Durable persistence
in `uploadStudy` runs strictly after validation, so an invalid upload never reaches
`ObjectStorage.put` and never produces a `domain_durable_object_assets` row.

## 12. IAM (documented only, not applied)

Minimum privilege for when Cloud Storage is actually deployed (Section 4 stays
code-ready-only until then):

- **Backend service account**: `roles/storage.objectCreator` +
  `roles/storage.objectViewer` scoped to the `studies/` prefix of the bucket (or a
  bucket dedicated to Backend-owned prefixes) -- create/read study inputs and, once
  wired, reports. No delete permission: this backend never calls
  `ObjectStorage.delete` in the DICOM/report flow.
- **AI module service account**: `roles/storage.objectCreator` +
  `roles/storage.objectViewer` scoped to `runs/` -- write outputs, read them back for
  rehydration.
- Explicitly avoid `roles/storage.admin` for either service account -- neither needs
  bucket-level IAM changes or the ability to delete arbitrary objects.

## 13. Remaining Cloud deployment steps

1. Provision (or reuse, per `CLOUD_05_STORAGE_DESIGN.md`'s `create_bucket=false` bucket
   module) a GCS bucket per environment.
2. Grant the two service accounts above their scoped roles.
3. Set `PFI_STORAGE_PROVIDER=gcs` + `PFI_STORAGE_GCS_BUCKET=...` for both services in
   the Cloud Run env.
4. Exercise this same upload -> restart -> reprocess evidence against a real Cloud Run
   revision restart (this pass only exercised local Docker restarts).
5. Wire report/PDF persistence (Section 9) before relying on GCS for the full clinical
   record.
