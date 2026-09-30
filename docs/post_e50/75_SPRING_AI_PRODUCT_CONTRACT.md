# Stage 75 — Spring Boot integration with the frozen AI Product contract

Branch: `research/post-e50-spring-ai-product-contract` (created from `enzo/p10-9-product-consolidation-backend`, commit `822c1a298131f4756ef9c5c419c2cf02a6bc8674`). Not committed/pushed/merged as of this document.

## 1. Architecture found

Spring Boot (Java 17, Maven) already had a mature AI integration layer before this stage — nothing here starts from a blank slate:

- **HTTP client**: `AiServiceClient` (WebClient, reactive but called with `.block(timeout)`), behind the `AiServiceOperations` interface. A second, smaller client (`AiProductCheckpointClient`) already talks to a *different* v2 surface (`/v2/product-checkpoint/contract`, `/v2/series-segmentation/...`) — not to be confused with this stage's `/v1/product/analyze`.
- **Contracts already modeled**: `AiMultiplanarV2ResponseDto`, `AiGovernanceV2Dto` (`humanReviewRequired`, `notClinicalDiagnosis`), `AiReviewPolicyV2Dto`, `DegenerativeFindingsV1Dto`. None of these is the `pfi.ai-product-result.v1` contract this stage adds.
- **Controllers**: `/api/ai/multiplanar/run`, `/api/studies/{caseId}/...` (runs, metadata), and ~12 other `Ai*Controller`s. No `/api/studies/{id}/analysis`-shaped endpoint existed.
- **Config**: `application.properties` (no YAML, no profiles) + `@ConfigurationPropertiesScan`. `AiServiceProperties` (`pfi.ai-service.*`) already carries `baseUrl`/`timeoutSeconds`.
- **Errors**: one `@RestControllerAdvice` (`ApiExceptionHandler`) + a central `ApiErrorCode` catalog. Per-subsystem exceptions (`AiMultiplanarUpstreamException`, `AiMultiplanarContractViolationException`) already established the pattern this stage follows.
- **Tests**: WireMock (`org.wiremock:wiremock-standalone`) is the established way to test `AiServiceClient` against a real HTTP server — no MockWebServer, no new library needed.

## 2. Where the real contract came from

`POST /v1/product/analyze` and `pfi.ai-product-result.v1` are **not invented here**. They were read directly from the AI Module repo (read-only, never modified — this stage only touches the Backend repo):

- `docs/contracts/ai-product-result-v1.schema.json` (JSON Schema, `$id: pfi.ai-product-result.v1`) — the authoritative response shape.
- `ai_service/pfi_ai_service/product_orchestration.py` — the `ProductAnalyzeRequest` pydantic model (request shape) and `run_product_analysis` (exact field construction).
- `ai_service/pfi_ai_service/api.py` — `@app.post("/v1/product/analyze")`.

## 3. Flow

```
Frontend
  → POST /api/studies/{caseId}/product-analysis      (AiProductAnalysisController)
  → AiAnalysisService.analyze(...)                    (75-D, no clinical interpretation)
  → AiServiceOperations.analyzeProduct(...)
  → AiServiceClient.analyzeProduct(...)                (75-A, WebClient, X-Trace-Id propagated)
      POST /v1/product/analyze  →  AI Module
  → AiProductContractValidator.validate(...)           (75-F, fail-closed)
  → AiProductAnalyzeResponseDto                          returned to Frontend as-is (200 OK)
```

## 4. Endpoint added

`POST /api/studies/{caseId}/product-analysis` — `AiProductAnalysisController`. `caseId` from the path (matches the existing `/api/studies/{caseId}/...` convention); body is `AiProductAnalysisApiRequestDto` (`sagittalInputId`, `axialInputId`, `metadata`), all optional except that at least one of the two input ids must be present (enforced in `AiAnalysisService`, `400` otherwise). The Frontend never sees the AI Module's base URL, inputId resolution, or model SHAs.

## 5. Request sent to the AI Module

`AiProductAnalyzeRequestDto` — `caseId`, `sagittalInputId`, `axialInputId`, `metadata` only. Never a local filesystem path, `PatientName`, `PatientID`, or a raw DICOM UID. `capabilities`/`inputIds` (present on the upstream contract) are deliberately **not sent** — the AI Module's own defaults already keep P10.7 pathology inference off; duplicating that policy here would be one more place it could drift from the frozen release.

`metadata` is merged with `traceId`/`backendTraceId`/`correlationId` from the current request's MDC trace id (same helper pattern as the existing multiplanar/pipeline calls) before the call is made.

## 6. Response modeled

`AiProductAnalyzeResponseDto` — an explicit Java record tree (no `Map<String,Object>`), matching the JSON Schema field-for-field: `schemaVersion`, `runId`, `traceId`, `caseId`, `release.releaseId`, `status`, `planes.{sagittal,axial}`, `discLevels[]` (`level`, `localization`, `axialPairing`, `measurements[]`, `degenerativeFindings`), `warnings[]`, `review`, `notClinicalDiagnosis`, `autonomousDiagnosis`, `humanReviewRequired`.

Every type is `@JsonIgnoreProperties(ignoreUnknown = true)` — the contract's nested objects do not declare `additionalProperties: false`, and a future AI Module field must never break deserialization here (see `application.properties`'s comment on why `spring.jackson.deserialization.fail-on-unknown-properties=true` needs this explicit per-DTO override).

**`DiscLevel.level` is nullable by contract** (`abstained`/`unavailable`/`not_requested` all carry `level: null`). Nothing in this stage defaults it to `"Unknown"`, `"L?"`, or any placeholder — it is passed through exactly as received.

## 7. `completed` / `partial` / `failed`

`AiAnalysisService.analyze(...)` returns the DTO **as-is**, regardless of `status`. This one design choice is what makes every HTTP-semantics distinction below fall out naturally:

| AI Module response | Backend behavior |
|---|---|
| HTTP 200, `status=completed` | 200 OK, DTO body |
| HTTP 200, `status=partial` | 200 OK, DTO body (never converted to an error) |
| HTTP 200, `status=failed` | 200 OK, DTO body (`status="failed"` is data, not an exception) |
| HTTP 4xx | `AiProductUpstreamException` → same status code, code `AI_INVALID_REQUEST` |
| HTTP 5xx / connection failure | `AiProductUpstreamException` → `502 Bad Gateway`, code `AI_MODULE_ERROR` |
| Timeout | `AiProductUpstreamException` → `504 Gateway Timeout`, code `AI_MODULE_TIMEOUT` |
| Contract/safety violation (any HTTP status) | `AiProductContractViolationException` / `AiProductSafetyViolationException` → `502 Bad Gateway` |

## 8. Contract validation (75-F) — fail closed

`AiProductContractValidator.validate(...)` runs inside `AiServiceClient.analyzeProduct(...)`, right after deserialization, before the result is returned to anything else (same place `runMultiplanarV1`/`runMultiplanarV2` already validate their own contracts):

1. `schemaVersion == "pfi.ai-product-result.v1"` (configurable, see §10) — else `AiProductContractViolationException`.
2. `release.releaseId == "post-e50-ai-product-freeze-v1"` (configurable) — else `AiProductContractViolationException`.
3. `humanReviewRequired == true`, `notClinicalDiagnosis == true`, `autonomousDiagnosis == false` — else `AiProductSafetyViolationException`, **regardless of `status`**. There is no configuration flag to relax this; a 200 response that fails this check is never exposed as valid.

A successful `aiCallsSucceeded` metric is only recorded once HTTP + deserialization + this validation all pass — an `AiProductContractViolationException`/`AiProductSafetyViolationException` also increments the existing `aiContractViolations` counter.

## 9. 67B2 / 67C / P10.7 handling

- **67B2** (`discLevels[].localization`): `status` can be `unavailable` (current readiness: `parityVerified=false`, `blocker=67B2_RUNTIME_PARITY_NOT_PROVEN`) or `abstained` — both keep `level=null`. Nothing here fabricates a level.
- **67C** (`discLevels[].axialPairing`): `validated=false`, `experimental=true`, `reviewRequired=true` are passed through unchanged — never presented as production-grade.
- **P10.7** (`discLevels[].degenerativeFindings`): `status="blocked_preprocessing_parity"`, `automaticInferenceEnabled=false`. This is a **capability not executed**, not a negative clinical finding — nothing in this stage (DTO, service, or tests) treats it as "no pathology."

## 10. Configuration added

```properties
pfi.ai-product-contract.expected-schema-version=${PFI_AI_PRODUCT_SCHEMA_VERSION:pfi.ai-product-result.v1}
pfi.ai-product-contract.expected-release-id=${PFI_AI_PRODUCT_RELEASE_ID:post-e50-ai-product-freeze-v1}
```

Bound by `AiProductContractProperties` (`@ConfigurationPropertiesScan` picks it up automatically). The AI Service base URL/timeout are **reused** from the existing `pfi.ai-service.*` properties — `/v1/product/analyze` is one more path on the same `aiWebClient`, not a new host.

## 11. Timeout

Reuses `AiServiceProperties.resolvedTimeoutSeconds()` (currently `${PFI_AI_TIMEOUT_SECONDS:180}`, floor 30s) — the same ceiling every other inference call on this client uses. No new timeout was introduced; documented here rather than duplicated in code.

## 12. Tests

30 new tests across 5 files (`mvn test` on JDK 21 — see §14 for why JDK 21):

- `AiProductAnalyzeResponseDtoTest` (5) — deserialization: null `level`, `validated=false`/`experimental=true` preserved, P10.7 `blocked_preprocessing_parity` never reinterpreted, unknown top-level field ignored, `warnings` preserved.
- `AiProductContractValidatorTest` (8) — valid passes; schemaVersion/releaseId mismatch → contract violation; `autonomousDiagnosis=true` / `notClinicalDiagnosis=false` / `humanReviewRequired=false` → safety violation (fail closed); null response.
- `AiServiceClientProductAnalyzeWireMockTest` (7) — `completed`/`partial`/`failed` all accepted at HTTP level; FastAPI 400 → client error; FastAPI 500 → upstream error; timeout → controlled 5xx (never hangs); wrong `release.releaseId` on an HTTP-200 body → contract violation, never a false success.
- `AiAnalysisServiceTest` (5) — `partial`/`failed`/`completed` returned as-is; at-least-one-input-id required; blank input ids normalized to `null` before calling the client.
- `AiProductAnalysisControllerTest` (5) — controller stays thin; `partial` never becomes a generic error; missing body handled; contract violation propagates; null `level` survives the full stack without an NPE.

## 13. Nullability

Every place `level` (or another contract-nullable field) crosses a boundary in this stage, it stays `null` when the AI Module says so. No code path in `AiProductAnalyzeResponseDto`, `AiAnalysisService`, or `AiProductAnalysisController` substitutes a placeholder string. Covered explicitly by tests 4/5/6/7/8 above.

## 14. Known environment issue (unrelated to this stage)

Running `mvn test` under the environment's default `JAVA_HOME` (JDK 25) fails 217 pre-existing tests with `Mockito cannot mock this class` (Mockito's inline mock maker is not yet compatible with JDK 25's class file format on this Mockito/ByteBuddy version). This reproduces on `main`/`enzo/p10-9-product-consolidation-backend` as well — it is **not** caused by this stage. Re-running with `JAVA_HOME` pointed at the installed JDK 21 (matching `pom.xml`'s `<java.version>17</java.version>` far more closely than JDK 25) gives a clean run: **640 tests, 0 failures, 0 errors, 54 skipped** (pre-existing skips, e.g. Testcontainers-gated tests).

## 15. Limits / what stage 75 deliberately does not do

- No persistence of the analysis result (stage 76).
- No async/background job — synchronous call only, per instructions.
- Request shape (`sagittalInputId`/`axialInputId`/`metadata`) mirrors the AI Module's real `ProductAnalyzeRequest`, but the Backend does not yet resolve these ids from a Study's own registration — the caller (Frontend, for now) still supplies them directly. Wiring that up to `StudyRunService`/existing input-registration flow is natural stage-76 scope.
- `capabilities` (`ProductCapabilities` on the AI side) is never sent — the AI Module's defaults are trusted as-is. If a future stage needs the Backend to *request* a specific capability set, that is a deliberate, separate decision (touches P10.7 activation policy) — not something to add incidentally here.
- No `/api/studies/{caseId}/product-analysis` GET (read-back) — nothing to read back yet without persistence.

## 16. Next stage (76)

- Persist `AiProductAnalyzeResponseDto` (or a derived summary) against the `Study`/`StudyRun` domain, mirroring how `MultiplanarRunPersistenceService` already persists multiplanar runs.
- Resolve `sagittalInputId`/`axialInputId` from the Study's own registered inputs instead of requiring the caller to pass them.
- Decide whether/how the Frontend should be able to re-fetch a past product-analysis result (a `GET`), once it is persisted.
- Revisit `capabilities` once 67B2/67C parity is proven and `productEnabled` flips — until then, this stage's silence on `capabilities` is itself the safe default.
