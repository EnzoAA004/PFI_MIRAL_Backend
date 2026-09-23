# Cloud Stage 7 — Full DICOM E2E (2026-09-23)

Goal: prove the full product Cloud pipeline with synthetic/non-PHI
DICOM data end to end: DICOM -> Backend Green -> GCS -> private AI ->
inference/assets -> durable GCS -> PostgreSQL -> Backend response ->
retrieval -> reprocess without re-upload -> cross-service durability
after independent restarts.

**Result: PASS.** Every stage of the pipeline was exercised with real
requests against the live Cloud services, not simulated. One honest
architectural finding is documented in section 6 (a `domain_studies`
catalog table exists in the schema but is not wired to any live code
path -- the actual live persistence mechanism for study identity is
`domain_durable_object_assets` + `domain_audit_events`).

## 1. Baseline verify (Step 1)

- Backend Green health: 200.
- Backend -> AI health (warm): 200, `aiModuleAvailable:true`.
- AI authenticated health: 200. AI unauthenticated: 403.
- Cloud SQL (`pfi-postgres-dev`): `RUNNABLE`, Postgres 15.
- Bucket (`pfi-backend-durable-dev`): reachable, `US-CENTRAL1`.

## 2. Fixture (Step 2)

Generated with pydicom, the same pattern as the AI repo's own
`ai_service/tests/test_study_zip_keeps_all_series.py` fixture (a
localizer plus sagittal T1/T2 and axial T1/T2 series) -- **not** real
patient data:

- 5 series, 30 slices total, 44,326 bytes.
- Pixel data: `numpy.random.default_rng` (seeded), 16x16, purely
  synthetic noise -- no real anatomy.
- No `PatientName`/`PatientID`/`PatientBirthDate` fields set at all.
- `caseId` used throughout: `STAGE7-E2E-SYNTHETIC-TEST` (clearly
  labeled, no PHI-like naming).

## 3. Initial state snapshot (Step 3)

| | Before |
|---|---|
| `domain_studies` rows | 0 |
| `domain_durable_object_assets` rows | 0 |
| Bucket total size | 982,626 bytes |
| `studies/` prefix objects | none |
| Backend revision | `pfi-backend-v2-dev-00002-hdw` |
| AI revision | `pfi-ai-module-00006-ppg` |

## 4. Upload through Backend Green (Step 4)

Real product endpoint, `POST /api/ai/studies` (multipart, demo bearer
token, no direct DB/GCS insertion):

```
HTTP 200, 4603 ms
studyId = 073f44ab7d1945579d570a9e91d3540f
5 series found, correctly classified:
  localizer (axial, multiplanar, not analyzable)
  t2_tse_sag_384 (sagittal, T2, analyzable)
  t1_tse_sag_320 (sagittal, T1, analyzable)
  t2_tse_tra_384 (axial, T2, analyzable)
  t1_tse_tra (axial, T1, analyzable)
```

`uploadStudy()` internally calls `aiServiceClient.uploadStudy(...)`
(confirmed by source read) -- this already invokes the private AI
module for ingestion/classification before Backend persists anything
durably.

## 5. Backend input durability (Step 5)

```
gs://pfi-backend-durable-dev/studies/073f44ab7d1945579d570a9e91d3540f/inputs/75a340fb-8c81-447f-94df-2e78f87cddfc/study.zip
  size: 44326 bytes (exact match to the uploaded fixture)
  generation: 1790171937320691
```

No public ACL (`gcloud storage objects describe ... acl` returned
empty -- no object-level public grants).

## 6. Database Study evidence (Step 6) -- with a real finding

`domain_durable_object_assets` (the table `persistStudyInputDurably`
actually writes to) has exactly 1 row after upload:

```
asset_id  = 75a340fb-8c81-447f-94df-2e78f87cddfc
study_id  = 073f44ab7d1945579d570a9e91d3540f
artifact_type = study_input
object_key = studies/073f44ab7d1945579d570a9e91d3540f/inputs/.../study.zip
size_bytes = 44326
storage_provider = gcs
```

**Finding**: `domain_studies` (a full catalog table with `case_id`,
`status`, `study_date`, `patient_id` FK, etc.) exists in the schema
and has a working repository/service class
(`PostgresStudyCatalogService`), but it is **not referenced anywhere
else in the codebase** (grepped) -- no controller wires it in. It
stayed at 0 rows throughout this entire E2E. The live, actually-used
persistence mechanism for "did this study happen" is
`domain_durable_object_assets` (keyed by a plain `study_id` text
column, no FK) plus `domain_audit_events` (see below) -- not the
`domain_studies` catalog. This is reported as observed, not silently
smoothed over; it does not block Stage 7's goal (durability of the
DICOM input and AI outputs), but is worth scoping into a future stage
if a real patient/study catalog view is needed.

`domain_audit_events` (distinct from the also-present, unrelated
`audit_events` table) correctly recorded the operation:

```
action=upload.study.completed
entity_id=073f44ab7d1945579d570a9e91d3540f
trace_id=trace-67389434-12d6-49da-851a-6172b319692d
metadata={"caseId":"STAGE7-E2E-SYNTHETIC-TEST","seriesCount":5,
          "planesDetected":["sagittal","axial"],"hasAxial":true,
          "hasSagittal":true}
```

## 7. AI invocation proof (Step 7)

Cloud Logging, `pfi-ai-module`, correlated by timestamp (no token
values in any entry):

```
13:58:53.550Z  200  3.423s   POST /inputs/study   (from the upload above)
```

A `200` on `httpRequest` is only reachable past Cloud Run's own IAM
check on the ID token -- platform-level confirmation of successful
authentication, the same evidence pattern used in Stage 6.

## 8. AI pipeline result (Step 8)

Real inference, not the demo/contract fallback -- explicitly requested
via `metadata.inferenceMode=real_baseline`:

```
POST /api/ai/pipeline/run {caseId, plane: sagittal,
  inputId: inp_4ce1fa2a1d474be2bd175bd5c88e8604,
  metadata: {inferenceMode: real_baseline}}
-> HTTP 200, 21352 ms
runId = 6c60404bdca1a206
modelKey = sagittal_spider, modelVersion = sagittal-spider-final-v1
artifactHash = cf11dcc0ad77a7c787e64a796a2fd7398ef906add461cef4b3d61f1a5238e944
inferenceMode = real_baseline, synthetic = false
realInferenceAvailable = true, runtime = pytorch, device = cpu
masks = [] (empty)
agentReasons = [real_baseline_inference_completed,
                real_inference_empty_foreground,
                real_inference_low_foreground_confidence]
agentStatus = requires_professional_review
```

**Scientific-claim discipline (explicit, per instruction)**: the empty
mask result is the *correct and expected* outcome, not a defect --
the fixture's pixel data is `numpy.random` noise, not real anatomy, so
a real model correctly finding no meaningful foreground and flagging
low confidence is the model working as intended, not evidence of
accuracy on real data. This run proves the **mechanical pipeline**
(Backend -> AI -> real PyTorch inference -> durable output), nothing
about clinical accuracy. Existing gates are unchanged by this stage:
sagittal validated metrics remain as previously documented; axial
quality gate remains failed; 67B2 remains offline-evidence-only; 67C
remains experimental; P10.7 is not called productive here or anywhere
in this report.

First `POST /pipeline/run` (without `inferenceMode=real_baseline`)
returned the demo/contract-fallback response in 18ms -- this is
`PipelineRunRequestDto`'s own documented default behavior when no
`inputPath` and no `real_baseline` metadata is given, not a bug;
recorded here for completeness since it was tried first before the
real-inference call above.

## 9. AI assets in GCS (Step 9)

```
gs://pfi-backend-durable-dev/runs/6c60404bdca1a206/sagittal/
  confidence.npy, input.png, mask-preview.png, mask.npy, overlay.png,
  slice-000..015.png (16), slice-000..015.raw (16)
  = 37 asset objects

gs://pfi-backend-durable-dev/runs/6c60404bdca1a206/sagittal/_meta/
  = 37 matching {assetName}.json metadata objects (Stage 5B's fix,
    confirmed working in the real product path, not just synthetic
    Stage 5B tests)
```

74 objects total under this run's prefix (37 + 37), independently
confirmed via `gcloud storage ls --recursive`.

## 10. Backend response (Step 10)

Already shown in section 8 -- full `PipelineRunRequestDto` response
shape, `series[].imageUrl`/`overlayUrl` pointing at Backend's own
`/api/ai/assets/...` proxy path (confirming the product architecture:
assets are served **through Backend**, not directly from AI, per
section 11).

## 11. Asset retrieval through product path (Step 11)

```
GET /api/ai/assets/6c60404bdca1a206/sagittal/input.png
  -> 200, 170 bytes (matches GCS-reported size exactly)
GET /api/ai/assets/6c60404bdca1a206/sagittal/overlay.png
  -> 200, 356 bytes (matches GCS-reported size exactly)
```

Both through Backend's product API (`/api/ai/assets/...`), which
internally proxies to AI's own `/assets/...` endpoint -- confirmed via
AI-side logs showing the corresponding `GET /assets/.../input.png` and
`/overlay.png` calls at matching timestamps.

## 12. Database result persistence (Step 12)

| Table | Before | After upload | After pipeline/run |
|---|---|---|---|
| `domain_durable_object_assets` | 0 | 1 | 1 (unchanged -- AI outputs live in AI's own GCS registry, not mirrored into Backend Postgres) |
| `domain_audit_events` | 2 (pre-existing, unrelated) | 3 (+upload.study.completed) | 4 (+pipeline.real_baseline.completed) |
| `domain_studies` | 0 | 0 | 0 (unwired, see section 6) |

## 13. Reprocess without re-upload (Step 13) -- PASS

```
POST /api/ai/studies/073f44ab7d1945579d570a9e91d3540f/reprocess?caseId=STAGE7-E2E-SYNTHETIC-TEST
(no file parameter sent -- the endpoint signature
 (@PathVariable studyId, @RequestParam caseId) structurally has none)
-> HTTP 200, 771 ms
5 series re-classified identically to the original upload
```

## 14. Reprocess evidence (Step 14)

- Original `studyId`: `073f44ab7d1945579d570a9e91d3540f` (reused --
  the reprocess call is scoped to it).
- AI's own per-call ingestion id for the reprocess response happened
  to differ (`8c7681192c2946e897b084cf27e3d297` in the response body)
  -- this is AI's own opaque per-ingestion identifier, unrelated to
  Backend's durable `study_id` key; it changing does not indicate a
  new upload occurred.
- `domain_durable_object_assets` row count: **unchanged at 1** --
  confirms no duplicate input write.
- `domain_audit_events` gained exactly one new row:
  `action=upload.study.reprocessed, entity_id=073f44ab7d1945579d570a9e91d3540f`.
- AI-side log: `POST /inputs/study -> 200, 0.118s` at the matching
  timestamp -- confirms Backend re-sent the durably-read bytes to AI,
  not a fresh client upload.
- **Re-upload required: NO.**

## 15-16. Backend restart proof / AI fresh-process rehydration (Steps 15-16)

Forced fresh instances of **both** services simultaneously (label-only
redeploys, no DB/GCS state touched):

```
pfi-backend-v2-dev-00003-8xf   (new, empty in-memory state)
pfi-ai-module-00007-km7        (new, empty in-memory state, empty local GCS cache)
```

Retrieval through the fresh Backend, itself calling the fresh AI:

```
GET /api/system/health -> 200 (fresh Backend instance)
GET /api/ai/assets/6c60404bdca1a206/sagittal/overlay.png
  -> 200, sha256 fbf6a95d618dd906d92f27baecd9b856ea22879c1d914706ac6d3184d9ed8298
     (byte-identical to the pre-restart download)
GET /api/ai/assets/6c60404bdca1a206/sagittal/input.png
  -> 200, sha256 55f74d109b525f483f8551235b1688eb1689591bef575a07ad6d2c231ed8fc3f
     (byte-identical to the pre-restart download)
```

## 17. Cross-service durability gate (Step 17) -- PASS

Both conditions held simultaneously:

- Backend memory lost (genuinely new revision/process).
- AI memory/cache lost (genuinely new revision, new local materialize
  cache).

And:

- Durable object row remains in SQL (`domain_durable_object_assets`,
  unchanged).
- DICOM input remains in GCS (`studies/.../study.zip`, unchanged).
- AI asset remains in GCS and rehydrates (checksums exact, section
  15-16).
- Asset is servable through the real product path (not a raw GCS
  fetch).
- Reprocess already proven to work without re-upload (section 13,
  performed *before* this restart, so the durable input's usability
  was independently established prior to forcing the restart).

## 18. Invalid input regression (Step 18)

```
POST /api/ai/studies with a non-ZIP text file
-> HTTP 400 BAD_REQUEST
   "El estudio debe subirse como archivo .zip con la serie DICOM."
```

Controlled, clear 4xx -- not a 500/502. This is Backend's own ZIP
validation, catching the malformed input before any AI call.

(One test-harness artifact along the way: a curl `-F
"file=@...;type=application/zip"` MIME-override syntax caused a local
`HTTP_STATUS:000` connection failure unrelated to any Cloud service --
removing the explicit `;type=` fixed it. Noted for transparency, not a
finding about the product.)

## 19. AI unavailable behavior (Step 19)

Not exercised live (no destructive outage test performed, per
instruction). Existing coverage: Stage 6's real cold-start test
already proved a controlled `degradedMode:true` response (not a
crash) when AI is slow to respond; the Backend's WireMock test suite
(`AiServiceClientProductAnalyzeWireMockTest`,
`AiServiceClientV2WireMockContractTest`, etc., all in the 1173/1173
passing suite) separately covers 401/403/404/5xx/timeout at the unit
level.

## 20. Cold start observation (Step 20)

This E2E's upload and first real inference call both landed on
already-warm instances (both services had been exercised minutes
earlier in Stage 6). The dedicated restart proof in sections 15-16 used
fresh revisions rather than a natural scale-to-zero wait, per
instruction ("a fresh revision is acceptable if faster"), so no
additional cold-start timing was captured this stage beyond what
Stage 5B (AI: ~21.9s cold) and Stage 6 (Backend/AI diagnostic-path
degraded-mode: ~10-11s bounded by the 10s diagnostic timeout) already
established. Real inference calls use the 180s timeout, not the 10s
diagnostic one, and are unaffected by that ceiling -- consistent with
this stage's real inference call (21.35s) completing normally on a
warm instance.

## 21. Performance (Step 21)

| Metric | Value |
|---|---|
| Upload (`/studies`) latency | 4,603 ms |
| Pipeline run (real inference) latency | 21,352 ms |
| Reprocess latency | 771 ms |
| Asset retrieval latency (warm) | 3-40 ms |
| Backend resources | 1 vCPU / 512 Mi |
| AI resources | 4 vCPU / 2 Gi |
| Backend instance count during test | 1 (concurrency=80, no scaling pressure from a single sequential test) |
| AI instance count during test | 1 (concurrency=1, min=0/max=1) |
| Cloud SQL | `RUNNABLE`, reachable throughout; connection pool behavior already validated in Stage 4B/4H (not re-measured here) |
| GCS bytes written this stage | 391,490 bytes (982,626 -> 1,374,116) |
| GCS objects written this stage | 75 (1 study input + 74 run-scoped AI objects) |

This is not the final cost benchmark (Stage 8/10 per the roadmap).

## 22. GCS object inventory (Step 22)

| OBJECT_TYPE | KEY_PATTERN | COUNT | TOTAL_BYTES | DURABLE_REQUIRED? | REGENERABLE? |
|---|---|---|---|---|---|
| Input DICOM ZIP | `studies/{studyId}/inputs/{assetId}/study.zip` | 1 | 44,326 | YES | NO (original upload) |
| AI run assets (images/arrays) | `runs/{runId}/{plane}/{asset}` | 37 | ~includes slices+masks+overlay | Effectively yes (source of truth for review) | Regenerable by reprocess, given the input is retained |
| AI per-asset durable metadata | `runs/{runId}/{plane}/_meta/{asset}.json` | 37 | small (JSON) | YES (Stage 5B rehydration depends on it) | Regenerable together with its asset via reprocess |
| Reports | none generated this stage | 0 | 0 | n/a | n/a |

## 23. SQL inventory (Step 23)

| TABLE/DOMAIN | BEFORE | AFTER | DELTA |
|---|---|---|---|
| `domain_studies` | 0 | 0 | 0 (unwired table, section 6) |
| `domain_durable_object_assets` | 0 | 1 | +1 (study input; AI outputs not mirrored here) |
| `domain_audit_events` | 2 (pre-existing) | 6 | +4 (`upload.study.completed`, `pipeline.real_baseline.completed`, `upload.study.reprocessed`, plus one further logged event) |
| `domain_ai_analysis_runs` | 0 | 0 | 0 (also unwired; AI run identity lives in AI's own GCS registry, not this table) |

No credentials exposed in any query or output above.

## 24. Security check (Step 24)

- AI invoker IAM policy: `{}` (empty) -- confirmed both before and
  after this stage. No `allUsers`, no `allAuthenticatedUsers`.
- Backend Green service account: `pfi-backend-runtime@...`, unchanged.
- No service-account JSON used anywhere (ADC + Cloud SQL Auth Proxy
  with ADC-based IAM auth for the local diagnostic DB queries in this
  report).
- No ID token or DB password value appears in this report or in any
  captured log/output (DB credentials were parsed into shell variables
  from Secret Manager and used directly in a single Docker invocation,
  never echoed; the local `.stage7_dbcreds` scratch file was deleted
  at the end of this stage).
- GCS bucket: no public ACLs observed on any object checked.

## 25. Cleanup policy (Step 25)

Per instruction, the synthetic test study/run were **not deleted**.
Retained for Stage 8 (persistence) and Stage 10 (cost) work:

```
SYNTHETIC_TEST_DATA -- no PHI
  studyId: 073f44ab7d1945579d570a9e91d3540f (caseId: STAGE7-E2E-SYNTHETIC-TEST)
  runId:   6c60404bdca1a206
  reprocess studyId: 073f44ab7d1945579d570a9e91d3540f (same study, second AI-side ingestion id 8c7681192c2946e897b084cf27e3d297)
```

## 26. Remaining blockers

None blocking Stage 8. One non-blocking architectural note carried
forward: `domain_studies` / `domain_ai_analysis_runs` are present in
the schema but unwired to any live code path -- worth a decision in a
future stage on whether a real study/analysis catalog is needed for
the product, or whether the current `domain_durable_object_assets` +
`domain_audit_events` combination is the intended long-term design.

## 27. Final summary

| Check | Result |
|---|---|
| Baseline verify | PASS |
| Upload through Backend Green | PASS, 200 |
| Backend input durability in GCS | PASS, exact size match |
| Database evidence | PASS (with the domain_studies finding, section 6) |
| Backend -> AI auth proof | PASS, correlated logs |
| AI real inference | PASS, real_baseline, mechanically correct |
| AI durable output assets | PASS, 37 assets + 37 metadata objects |
| Asset retrieval via product path | PASS, size/checksum match |
| Database result persistence | PASS (audit trail complete) |
| Reprocess without re-upload | **PASS** |
| Backend restart persistence | PASS |
| AI fresh-process rehydration | PASS, checksums exact |
| Cross-service durability gate | **PASS** |
| Invalid input regression | PASS, controlled 400 |
| Security gate | PASS, AI remains private |

**FULL_CLOUD_E2E = PASS**
**DURABILITY_AFTER_RESTART = PASS**
**REPROCESS_WITHOUT_REUPLOAD = PASS**
**READY_FOR_FINAL_PERSISTENCE_COST_STAGE = YES**
