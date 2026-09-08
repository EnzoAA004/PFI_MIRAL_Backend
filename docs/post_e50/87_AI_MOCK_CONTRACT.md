# Stage 87 — AI Module Mock Contract (`mock-ai.mjs`)

Branch: `research/post-e50-spring-ai-product-contract` @ `8f7da97` (Stage 86). Not committed/pushed as of this document. **Zero Backend source changes** — only `docker/post-e50-mocks/mock-ai.mjs` (rewritten) and a new standalone test file for it.

## Why this stage exists

Stage 86 found, and documented as explicitly out of scope, that `docker-compose.post-e50.yml`'s `mock-ai` service only implemented the newer product-analysis pipeline (`POST /v1/product/analyze`) — not the older multiplanar/upload pipeline (`POST /inputs`, `POST /v2/multiplanar/run`) that `NewAnalysisDrawer.tsx` still calls to actually create a Study. Empirically confirmed via curl: `POST /api/ai/inputs` returned `502 UPSTREAM_UNAVAILABLE`. Stage 87 closes this gap by extending the mock — never the Backend, never the product's real architecture.

## 87-B Network contract capture (real routes, not inferred)

Traced directly from `AiServiceClient.java` (the Backend's only HTTP client to the AI Module):

| Method | Path (on the AI Module) | Called by | Request | Response DTO |
|---|---|---|---|---|
| GET | `/health` | (already existed) | — | `{status}` |
| POST | `/inputs` | `AiServiceClient.uploadInput` | multipart: `file`, `caseId`, `plane` | `AiInputResponseDto{inputId, caseId, plane, format, size}` |
| POST | `/v2/multiplanar/run` | `AiServiceClient.runMultiplanarV2` (the contract version this stack is configured for — `PFI_AI_SERVICE_MULTIPLANAR_CONTRACT_VERSION=v2`) | `AiMultiplanarV2RequestDto{caseId, traceId, inferenceMode, allowContractFallback, planes:{sagittal,axial}, options}` | `AiMultiplanarV2ResponseDto` (full pfi.multiplanar-run.v2 shape) |
| POST | `/v1/product/analyze` | (already existed, Stage 81) | — | unchanged |
| GET | `/inputs/{inputId}/slices/{index}` | `AiServiceClient.getSeriesSlice` (proxied by `AiBackendService.getSeriesSlice` at `GET /api/ai/series/{inputId}/slices/{index}`) | — | PNG bytes |

**Stage 89 addendum**: the multi-slice viewer route (`GET /inputs/{inputId}/slices/{index}`) was added, and — more importantly — `deterministicInputId()`'s output shape was corrected from `mock-input-*` to `inp_<32-hex>`. The Backend's `AiBackendService.getSeriesSlice` validates every inputId against `INPUT_ID_PATTERN = ^inp_[0-9a-f]{32}$` (the real AI Module's own shape) *before* ever forwarding upstream — a `mock-input-*` id failed that check with a `400`, never reaching this mock's own (already-correct) slice route at all. See `89_DEMO_MODE.md` for the full root-cause trace. This is the canonical inputId shape now — any future mock endpoint that echoes or derives an inputId should use this same generator.

No endpoint was invented that the Backend never calls — `/inputs/study` (`uploadStudy`, the whole-archive-ZIP upload) was deliberately **not** added: nothing in this stage's fixture or E2E path exercises it, and the spec's own 87-C says "no crear endpoints ficticios que Backend nunca llama." A future stage that needs the ZIP-upload path can extend the mock the same way this one did.

## 87-D/87-E Upload contract

`POST /inputs` accepts real `multipart/form-data` (parsed by a small hand-rolled parser — the mock's Docker image has no dependencies, see `Dockerfile`) and returns:

```json
{ "inputId": "mock-input-case-87-a3-sagittal-1c75783d6aeb", "caseId": "CASE-87-A3", "plane": "sagittal", "format": "dcm", "size": 39 }
```

`inputId` is `mock-input-<sanitized-caseId>-<plane>-<random-hex>` — deterministic in shape, unique per upload, never a real DICOM UID or filesystem path (87-E). A request with no `file` part returns `400 MISSING_FILE`.

## 87-F Multiplanar run contract — templated from the real, already-tested fixture

Rather than inventing a new shape, this mock's `POST /v2/multiplanar/run` handler templates the exact wire shape already verified by `AiServiceClientV2WireMockContractTest` against `src/test/resources/contracts/ai-module-multiplanar-v2-real-baseline.json` — the same fixture the Backend's own test suite trusts. Only request-specific identifiers (caseId, traceId, run/plane/input ids) are substituted.

**A real constraint discovered while building this**: `MultiplanarV2RealBaselineValidator` enforces a **frozen-artifact policy** for `sagittal_spider`/`sagittal-spider-final-v1` specifically — exact `model.version`/`model.artifactHash` values, and a floor of ≥3 masks, ≥3 landmarks, ≥9 measurements. The mock hardcodes these exact frozen values (`frozenSagittalArtifacts()`) whenever the request's sagittal `modelKey` is `sagittal_spider` (the only model key `NewAnalysisDrawer.tsx` ever sends) — a naive dynamically-generated response fails this validator immediately (confirmed empirically during development: the first draft failed with fewer masks/landmarks and a non-frozen `artifactHash`). Axial (when requested) uses a simpler, non-frozen synthetic model, since the validator does not apply the same floor to it.

`runId` must start with `"multi-"` (also validator-enforced) — the mock generates `multi-<random-hex>`.

## 87-G Product analysis mock — unchanged

`POST /v1/product/analyze` is byte-for-byte the same function as Stage 81 shipped: `partial` status, one `L4-L5` disc level, the `67B2_RUNTIME_PARITY_NOT_PROVEN`/`67C_RUNTIME_PARITY_NOT_PROVEN` warnings, `blocked_preprocessing_parity` for degenerative findings. Verified unchanged by the new contract tests (§ below, cases 8–9).

## 87-I Mock state strategy: stateless

No database, no shared file, no cross-request memory beyond what a single request's own body supplies. An `inputId` from a prior upload is never validated for existence on the run endpoint (confirmed by contract test #6) — this mock trusts the caller's own `inputId`, matching this whole system's actual trust boundary (the real AI Module would be the one to reject an unknown input; this mock's only job is to prove the wire contract, not simulate storage). A `docker compose down`/`up` cycle resets nothing that mattered, because nothing was kept.

## 87-J Error modes — a `caseId` substring convention

Deterministic, per-request, no headers or query params needed (the caseId is already the one identifier every caller has in hand):

| Trigger | Effect |
|---|---|
| `caseId` contains `MOCK-FAIL-UPLOAD` | `POST /inputs` → `500 {code: "MOCK_UPLOAD_FAILURE"}` |
| `caseId` contains `MOCK-FAIL-RUN` | `POST /v2/multiplanar/run` → `500 {code: "MOCK_RUN_FAILURE"}` |
| `caseId` contains `MOCK-TIMEOUT-RUN` | `POST /v2/multiplanar/run` never responds (exercises the Backend's own client-side timeout) |

A whole-container fallback, `MOCK_AI_MODE=error` (mirroring `mock-llm.mjs`'s existing convention), is also available for the rare case a whole-stack failure mode is more convenient — the per-caseId convention is what every Stage-87 E2E scenario actually uses, since it lets one running stack exercise both the happy path and failure modes without a restart.

## 87-K No clinical semantics

Every mask/landmark/measurement is a synthetic, technical value (`vertebra_group`, `canal`, `disc_group` with placeholder areas/widths/heights) — never a diagnostic claim. Verified explicitly by contract test #12 (a regex for "hernia confirmada"/"patología diagnosticada"/"tratamiento recomendado"/"diagnóstico confirmado" matches nothing in either mock response).

## 87-AA Mock contract tests

New standalone script, `docker/post-e50-mocks/mock-ai-contract-tests.mjs` — spawns the real `mock-ai.mjs` as a child process on a throwaway port and talks real HTTP to it (no Backend, no Docker required to run this file). Not wired into `mvn test` (this repo has no JS test runner for Backend scripts) — run directly with `node docker/post-e50-mocks/mock-ai-contract-tests.mjs`.

13 tests (exceeds the 12 required): health; upload success; upload response shape; invalid upload (no file part → 400); run success; run with an unknown/unvalidated inputId (proves 87-I's statelessness deliberately); sagittal_spider frozen artifact counts/model fields; product analyze unchanged; product analyze warnings/P10.7 unchanged; upload error mode; run error mode; timeout mode (bounded 1.5s race, not the full real Backend timeout — proving "never responds" without slowing the test suite); no clinical language anywhere. **All 13 pass.**

## Mock files

**Modified**: `docker/post-e50-mocks/mock-ai.mjs` (added `/inputs`, `/v2/multiplanar/run`; `/health` and `/v1/product/analyze` untouched).

**New**: `docker/post-e50-mocks/mock-ai-contract-tests.mjs`.

## What this stage deliberately does not do

- No change to `mock-llm.mjs` (already fully functional since Stage 81, confirmed still working in this stage's own chat E2E evidence).
- No `/inputs/study` (ZIP upload) route — not exercised by this stage's fixture.
- No persistence/state in the mock beyond a single request — matches 87-I's explicit preference for stateless.
- No change to any Backend Java file, DTO, or contract — the mock was made to fit the Backend's real, already-tested contract, not the other way around.
