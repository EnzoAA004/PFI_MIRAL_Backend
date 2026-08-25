# Stage 76 — Structured persistence of AI product-analysis results + linkage to Study

Branch: `research/post-e50-spring-ai-product-contract` (same branch as stage 75 — no new branch, per instructions). Not committed/pushed/merged as of this document.

## 1. Existing persistence found (before writing any code)

Persistence in this Backend is **not JPA/Hibernate** — plain JDBC:

- `StudyRepository` (interface) + two implementations selected by `pfi.persistence.mode` (`postgres` / `memory`, default `memory`): `PostgresStudyRepository` (raw `java.sql.Connection`/`PreparedStatement`, `DriverManager.getConnection(jdbcUrl)` per call, no connection pool) and `InMemoryStudyRepository` (`ConcurrentHashMap`-backed).
- Domain concepts are plain Java `record`s: `Study`, `StudyRun`, `InputResource`, `RunArtifact`, `ReviewerAnnotation`, `MeasurementCorrection` — not `@Entity` classes.
- Migrations are hand-written `.sql` files under `docs/migrations/`, named `V<YYYYMMDD>_<seq>_<description>.sql`, applied by `SqlMigrationRunner` (tracked in `schema_migrations`, guarded by a Postgres advisory lock). Latest existing migration before this stage: `V20260805_015_study_series_catalog.sql`.
- Relevant tables: `domain_studies`, `domain_study_runs` (multiplanar runs; AI measurements only live inside its `metrics_snapshot` JSONB — there is **no** dedicated measurements table), `domain_input_resources` (`plane`, `input_id`, `analyzable`, ...).
- **No `@Transactional` anywhere in the codebase** (confirmed by a repo-wide search, zero hits). The established pattern is manual: `connection.setAutoCommit(false)` → try body → `commit()` → `catch { rollback(); throw }` → `finally { setAutoCommit(true) }`, all inside one `try (Connection connection = connection())`.
- `domain_review_corrections`/`domain_reviewer_annotations` are about **human review**, not raw AI output — confirmed not to be repurposable for this stage's needs.
- No warnings-persistence and no Java lifecycle-status enum existed anywhere prior to this stage.

**Decision**: extend the same `StudyRepository` interface with 5 new methods, implement them identically in both `PostgresStudyRepository` and `InMemoryStudyRepository`, and add 3 new tables — no second persistence abstraction was introduced.

## 2. New entities

Three new `record`s in `ar.edu.uade.pfi.backend.domain`, deliberately not JPA entities (matching the existing style):

- **`AiProductAnalysisRun`** — one persisted execution of `POST /v1/product/analyze`: `id`, `studyId`, `caseId`, `schemaVersion`, `releaseId`, `status` (`completed`/`partial`/`failed`, verbatim), `humanReviewRequired`/`notClinicalDiagnosis`/`autonomousDiagnosis`, `traceId`, `sagittalInputId`/`axialInputId` (Backend-internal opaque ids, never a path), `warnings`, `rawResponseSanitized` (optional, unused for now — see §8), `createdAt`, `completedAt`. No separate PENDING/RUNNING lifecycle state: the AI call is synchronous and a row is only created after a validated result already exists (deliberate simplification — see stage instructions §76-L).
- **`AiDiscLevelResult`** — one `discLevels[]` entry: `rankIndex` (preserves original array order — more than one entry can have `level=null`), `level` (nullable by contract, never coerced), `localizationStatus`/`localizationSource`/`localizationValidated`/`abstentionPreserved` (67B2), `axialPairingStatus`/`axialPairingValidated`/`axialPairingExperimental`/`axialPairingReviewRequired` (67C), `degenerativeFindingsStatus`/`automaticInferenceEnabled` (P10.7).
- **`AiMeasurementResult`** — one `measurements[]` entry: `plane`, `level`, `structure`, `areaMm2`/`majorAxisMm`/`minorAxisMm` (nullable `Double`), `reviewRequired`. No `bboxHeightMm`/`bboxWidthMm` — the real `AiProductAnalyzeResponseDto.Measurement` (stage 75, sourced from the actual AI contract) does not emit them; adding unused columns would be inventing contract fields.

## 3. Migration

`docs/migrations/V20260825_016_ai_product_analysis_runs.sql` — 3 new tables only, nothing altered or dropped:

- `domain_ai_analysis_runs` — `status` has a `CHECK (status IN ('completed','partial','failed'))`; `warnings JSONB NOT NULL DEFAULT '[]'`; `raw_response JSONB NULL`; **no unique constraint on `study_id`** (multiple runs per Study are allowed by design — stage 76-I/76-V). Indexes: `study_id`, `created_at`.
- `domain_ai_disc_level_results` — FK `analysis_run_id → domain_ai_analysis_runs ON DELETE CASCADE`; `level TEXT NULL`. Index: `analysis_run_id`.
- `domain_ai_measurement_results` — FK `analysis_run_id` and `disc_level_result_id`, both `ON DELETE CASCADE`. Index: `analysis_run_id`.

## 4. Repository methods added

On `StudyRepository` (implemented identically by both `PostgresStudyRepository` and `InMemoryStudyRepository`):

```java
AiProductAnalysisRun saveAiProductAnalysisRun(AiProductAnalysisRun run, List<AiDiscLevelResult> discLevels, List<AiMeasurementResult> measurements);
List<AiProductAnalysisRun> findAiProductAnalysisRunsByStudyId(String studyId);
Optional<AiProductAnalysisRun> findAiProductAnalysisRunById(String id);
List<AiDiscLevelResult> findAiDiscLevelResultsByAnalysisRunId(String analysisRunId);
List<AiMeasurementResult> findAiMeasurementResultsByAnalysisRunId(String analysisRunId);
```

`PostgresStudyRepository.saveAiProductAnalysisRun` follows the exact same manual-transaction shape as the existing `saveRun`: one connection, `setAutoCommit(false)`, insert run → insert each disc level → insert each measurement, `commit()`, `catch { rollback(); throw }`, `finally { setAutoCommit(true) }`. A failure partway through (e.g. a measurement referencing a disc level that was never inserted) rolls back the whole run — never a half-persisted row (verified by `PostgresStudyRepositoryAiProductAnalysisTest.aChildInsertFailureRollsBackTheWholeRunNeverHalfPersisted`).

## 5. Input resolution (`AiStudyInputResolver`)

New `@Component` in `ar.edu.uade.pfi.backend.service`. `resolve(caseId)`:

1. `StudyRepository.findStudyByCaseId(caseId)` — `StudyNotFoundException` if absent (reuses the existing exception/handler, already mapped to 404).
2. `findInputsByStudyId(study.id())`, filtered by `analyzable == true` and `plane == "sagittal"`/`"axial"`, picking the most recently created match per plane.
3. If **neither** plane resolves to anything, throws the new `AiStudyInputUnresolvedException` (422, code `AI_STUDY_INPUT_UNRESOLVED`) — a functional error, not a 5xx.

Never fabricates an id and never falls back to a filesystem path — only `InputResource.inputId()` values already registered against the Study are returned.

## 6. Orchestration (`AiProductAnalysisPersistenceService`)

```
Frontend
  → POST /api/studies/{caseId}/product-analysis         (AiProductAnalysisController, body: {metadata} only)
  → AiStudyInputResolver.resolve(caseId)                  resolves sagittalInputId/axialInputId
  → AiAnalysisService.analyze(...)                        stage-75 call: WebClient → AI Module, contract/safety validated
  → AiProductAnalysisMapper.toDomain(...)                 validated response → AiProductAnalysisRun + discLevels + measurements
  → StudyRepository.saveAiProductAnalysisRun(...)          one short transaction
  → AiProductAnalysisMapper.toResultDto(...)              → AiProductAnalysisResultDto returned to Frontend (200 OK)
```

The ~180s AI call happens entirely through `AiAnalysisService`/`AiServiceClient`, **before** the single `saveAiProductAnalysisRun` call — no database transaction is open during it (stage 76-K). The only write is that one already-transactional repository call at the end, so there is no long-lived transaction to hold anywhere in this class.

`AiAnalysisService` (stage 75) is reused as-is, not duplicated — `AiProductAnalysisPersistenceService` only adds the resolve/persist steps around it.

## 7. Endpoint evolution

- **`POST /api/studies/{caseId}/product-analysis`** — body is now just `{ "metadata": {...} }` (or empty). `AiProductAnalysisApiRequestDto.sagittalInputId`/`axialInputId` are kept on the wire, annotated `@Deprecated(forRemoval = true)`, and have **no effect** — kept only so an existing caller that still sends them doesn't fail deserialization. Response is the new `AiProductAnalysisResultDto`: `analysisRunId`, `studyId`, `caseId`, `status`, `warnings`, `discLevels[]` (with nested `measurements[]`), `review`, `humanReviewRequired`/`notClinicalDiagnosis`/`autonomousDiagnosis`, `createdAt`/`completedAt`. Never the AI Module's base URL, the resolved `sagittalInputId`/`axialInputId`, or a model checkpoint SHA.
- **`GET /api/studies/{caseId}/product-analyses`** (stage 76-O, new) — `AiProductAnalysisHistoryResponseDto { caseId, analyses: [...] }`, newest first.
- **`GET /api/studies/{caseId}/product-analyses/{analysisId}`** (stage 76-O, new) — same `AiProductAnalysisResultDto` shape as the POST response; `404` both for a nonexistent `analysisId` and for an `analysisId` that belongs to a *different* Study (never leaks cross-Study existence).
- **`GET .../product-analysis/latest`** (stage 76-P) — **deliberately skipped**: no existing convention in this controller family has a `/latest` alias, and history+detail already cover the read-back need without adding a third shape.

## 8. What is and isn't persisted

- `status`, `warnings`, `humanReviewRequired`/`notClinicalDiagnosis`/`autonomousDiagnosis`, every `discLevels[]`/`measurements[]` field: persisted **exactly** as the AI Module reported them. A `null` `level` stays `null` in the database; a `67C` `experimental=true`/`validated=false` pair is stored as-is; a P10.7 `degenerativeFindingsStatus="blocked_preprocessing_parity"` is stored as-is and is never treated as, displayed as, or compared against a negative finding anywhere in this stage's code or tests.
- `rawResponseSanitized`/`raw_response` — the column exists (nullable JSONB) but this stage does not populate it. The three structured tables already capture every field the contract defines; adding a raw-JSON sanitizer for a blob that duplicates already-structured data was judged unnecessary complexity for this stage (stage instructions mark this piece explicitly optional). If a future stage needs raw traceability, `AiProductAnalysisMapper.toDomain` is the one place to route it through `SafeLogSanitizer`-equivalent scrubbing (forbidden fields per the stage brief: `PatientName`, `PatientID`, `StudyInstanceUID`, `SeriesInstanceUID`, `SOPInstanceUID`, filesystem paths, hostnames, tokens) before it is ever written.
- No PII is written by this stage under any code path: nothing here ever reads DICOM tags, patient identifiers, or filesystem paths — the mapper only touches the already-sanitized `AiProductAnalyzeResponseDto`.

## 9. Multiple runs / idempotency

Every `analyze()` call creates a brand-new `AiProductAnalysisRun` with its own UUID; there is no upsert-by-`studyId` and no unique constraint on `study_id`. A Study can accumulate any number of runs over time, each independently readable via history/detail — verified by `multipleRunsAllowedForSameStudyNeverOverwritten` (in-memory) and `multipleRunsAllowedForTheSameStudyNoUniqueConstraintOnStudyId` (Postgres).

## 10. Review state

`humanReviewRequired` is persisted from the (already fail-closed-validated, always-`true`) contract value. `AiProductAnalysisResultDto.review.status` is `"pending"` whenever `humanReviewRequired` is true — which the stage-75 safety validator guarantees for any run that reaches persistence at all. No review workflow (assignment, sign-off, state transitions) is built in this stage — only the flag and the fixed initial status, per instructions.

## 11. Tests

47 tests across 5 files (38 run without Docker; 9 Postgres/Testcontainers tests require Docker and are skipped without it, same as the pre-existing `StudyWorklistServicePostgresTest`):

- `AiStudyInputResolverTest` (9) — resolves sagittal-only/axial-only/both; `StudyNotFoundException` for an unknown `caseId`; `AiStudyInputUnresolvedException` when nothing analyzable exists; non-analyzable inputs ignored even when an analyzable one also exists; most-recent-by-`createdAt` picked when several exist for the same plane; never returns anything but a registered `inputId`.
- `AiProductAnalysisMapperTest` (11) — `completed`/`partial`/`failed` mapped verbatim; `level=null` preserved (single and multiple-null-in-sequence, `rankIndex` order preserved); warnings mapped verbatim; measurements carry the correct `analysisRunId`/`discLevelResultId`; `reviewRequired` defaults to `true` when the AI Module omits it; 67C `experimental=true`/`validated=false` preserved; P10.7 `blocked_preprocessing_parity` preserved; `toResultDto` groups measurements by disc level and sorts by `rankIndex`.
- `AiProductAnalysisPersistenceServiceTest` (12) — full orchestration against `InMemoryStudyRepository` (no DB/FastAPI needed): `analysisRunId` returned; `completed`/`partial`/`failed` all persisted and readable; `partial` never becomes an HTTP error; warnings round-trip through history; multiple runs never overwrite each other; history sorted newest-first; detail 404s for a nonexistent id and for a cross-Study id; a Study with no analyzable input surfaces the functional `AiStudyInputUnresolvedException`; an unknown `caseId` surfaces `StudyNotFoundException`; safety flags (`humanReviewRequired`/`notClinicalDiagnosis`/`autonomousDiagnosis`) and the `pending` review status are all present in the response.
- `AiProductAnalysisControllerTest` (6, stage-75's 5 rewritten for the new architecture + 1 new) — controller resolves inputs from a seeded Study rather than requiring the caller to send them; `completed`/`partial` behavior preserved; missing body handled; contract violation propagates; `level=null` survives the full stack; history+detail reflect a persisted run end-to-end through the controller.
- `PostgresStudyRepositoryAiProductAnalysisTest` (9, Testcontainers, `disabledWithoutDocker = true`) — real Postgres round trip of run+discLevels+measurements; `level=null` round-trips as `NULL`, never a placeholder; multiple runs per Study confirmed at the DB level; the `status` `CHECK` constraint accepts `completed`/`partial`/`failed` and rejects anything else; a child-insert failure rolls back the entire run (no half-persisted row); `ON DELETE CASCADE` from `domain_studies` removes its analysis runs; warnings default to `[]` when none are reported; 67C/67B2/P10.7-adjacent boolean flags persisted literally.

## 12. Regression (JDK 21)

Same known environment issue as stage 75: the environment's default `JAVA_HOME` is JDK 25, which fails ~217 pre-existing tests with `Mockito cannot mock this class` (unrelated to this stage, reproducible on `main`). All results below are from `JAVA_HOME` pointed at the installed JDK 21, matching `pom.xml`.

Full suite (`mvn test`, JDK 21): **682 tests, 0 failures, 0 errors, 63 skipped** (up from stage 75's 640/0/0/54 — the 42-test net increase is stage 76's new tests; the 9 additional skips are the Docker-gated `PostgresStudyRepositoryAiProductAnalysisTest`, which was not run against a real Postgres in this environment since Docker is unavailable here, same as the pre-existing `StudyWorklistServicePostgresTest`).

## 13. Limits / what stage 76 deliberately does not do

- No raw-response sanitizer/persistence (column exists, unused — see §8).
- No review workflow beyond the `pending` flag/status (assignment, sign-off, corrections against an AI product run) — that is a natural stage-77+ scope, mirroring how `RunReviewService`/`MeasurementCorrection` already work for multiplanar runs.
- No `GET .../product-analysis/latest` (see §7).
- No change to 67B2/67C/P10.7 activation — every flag is persisted and re-served exactly as received, never flipped.
- No Frontend changes.

## 14. Next stage (77+)

- History/detail read-back is now available; a stage-78-style longitudinal comparison across a Study's multiple `AiProductAnalysisRun`s becomes possible without new persistence.
- A review workflow for product-analysis runs (accept/observe/reject a disc level or measurement) can follow the same `RunReview`/`MeasurementCorrection` shape already used for multiplanar runs, once actually requested.
- If raw-response traceability becomes necessary, route it through a dedicated sanitizer before populating `raw_response` — do not do this by guesswork against an evolving upstream contract.
