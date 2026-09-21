# Stage 85 — Patient List / Navegación de Pacientes (Backend)

Branch: `research/post-e50-spring-ai-product-contract`. Base commit `653b281` (Stage 84). Not committed/pushed as of this document. **Zero new migrations** — this stage runs entirely on the existing `domain_studies`/`domain_ai_analysis_runs`/`domain_ai_analysis_run_reviews` schema.

## 1. Architecture decision (closed, per the stage brief)

**Patient entity: NOT PRESENT.** No `Patient` table, no domain class, no FK, no Study schema change. The "patient list" the Frontend needs is a **projection/aggregate over `Study.subjectRef`**, computed on read — never a persisted entity. This is the same closed decision Stage 84 made for patient history, extended one level up: where Stage 84 answers "give me every Study for this one subjectRef," Stage 85 answers "give me one summary row per distinct subjectRef."

## 2. Inventory (85-A) — what already existed, reused verbatim

- `StudyRepository.findAllStudies()` already existed (confirmed by inspection before writing any new code) — used as the single source query for the new projection, no new repository method needed.
- `EffectiveAnalysisRunSelector.selectEffectiveRun(studyId)` (Stage 79) and `StudyRepository.findAiProductAnalysisRunReview(analysisRunId)` (Stage 78) — reused exactly, unmodified, for "which run/review represents this subject's latest Study," the same rule Stage 84's history and Stage 80's chat already use.
- `StudyRepository.findStudiesBySubjectRef`'s normalization convention (trim + case-insensitive, `null` never matches) — reused in spirit (§3), not by direct delegation, since this service aggregates *all* Studies rather than looking up one subjectRef.
- **Gap confirmed, not assumed**: grep across every controller found no `/api/patients` mapping anywhere in the Backend — `PatientsView.tsx`'s `searchPatients`/`patientApi.ts` calls were pointed at an endpoint that has never existed, matching Stage 84's own finding about the (now-deleted) `PatientDetailView.tsx`.

## 3. `SubjectListService` (85-B/85-C/85-D/85-F/85-G/85-K/85-S)

New service, single public method `list(): List<SubjectSummaryDto>`:

1. Loads every Study via `findAllStudies()` (one query — §7 explains why this is not N+1 per subject).
2. Groups Studies by a normalized key (`trim().toLowerCase()`), **excluding** any Study whose `subjectRef` is `null`/blank (85-D: a null-subjectRef Study never forms a row, and multiple null-subjectRef Studies never merge into a placeholder "unknown patient" — verified by dedicated tests and live in Docker).
3. For each group, selects the **latest Study** by `studyDate` (nulls-first, so a real date always outranks a missing one) with a deterministic `caseId` tie-break (85-F).
4. Resolves that latest Study's **effective analysis run** via `EffectiveAnalysisRunSelector` (85-G — never "first run," never an arbitrary latest row) and its review status (defaults to `"pending"` when no review row exists, same convention as Stage 84).
5. Sorts the resulting rows by `latestStudyDate DESC` (nulls-last), tie-broken by `subjectRef` (85-K).

The **displayed** `subjectRef` in the DTO is the latest Study's own raw-cased value (not the lowercased grouping key), so a Frontend navigating with it into `GET /api/subjects/{subjectRef}/history` always uses a casing that really exists.

## 4. `SubjectSummaryDto` (85-T)

```json
{
  "subjectRef": "PAT-85-A",
  "studyCount": 3,
  "latestStudyDate": "2026-06-01",
  "latestCaseId": "CASE-85-A3",
  "latestAnalysisStatus": "completed",
  "latestReviewStatus": "accepted"
}
```

`latestAnalysisStatus`/`latestReviewStatus` are `null` together when the latest Study has no effective run yet — a normal state (a Study can legitimately have zero analyses), never an error. `warningCount` was deliberately **not** added: the spec marked it optional-if-cheap, and computing it would require loading the effective run's warning list for every subject on every list request, which is more work than this summary's own "lightweight, never Study/measurement detail" mandate (85-E) justifies; a professional who needs warnings opens Stage 84's history view, which already surfaces them per Study.

## 5. Endpoint (85-B)

```
GET /api/subjects
```

Added to the existing `SubjectHistoryController` (same `@RequestMapping("/api/subjects")`, same constructor-injection/`accessAuditService.record(...)` pattern as the three pre-existing endpoints) rather than a new `/api/patients` controller — per the spec's explicit "no fictitious endpoint just to keep the old Frontend working," and to keep every subject-identity concept under one coherent route prefix.

## 6. Identity/normalization (85-C)

Grouping key = `subjectRef.trim().toLowerCase(Locale.ROOT)`, mirroring `findStudiesBySubjectRef`'s SQL rule. `SubjectReferenceValidator.normalize` was deliberately **not** invoked here: that class validates a single *caller-supplied* subjectRef (throwing on an invalid one), which has no meaning for a service that aggregates every Study's own already-persisted `subjectRef` values — there is no external input to reject, only internal values to group. Reusing it would mean catching-and-ignoring its exception on every malformed legacy value, which is worse than the small, purpose-built `normalizeKey` helper actually used.

## 7. Query efficiency (85-S)

One `findAllStudies()` query, then in-memory grouping, then one `EffectiveAnalysisRunSelector` call (itself one query) **per distinct subject** — bounded by the number of subjects, not the number of Studies. This mirrors Stage 84's own accepted N+1-per-subject shape (§10 of `84_PATIENT_HISTORY_BACKEND.md`) and is a deliberate MVP-scale decision: at today's realistic dataset size (single/double-digit subjects), a full scan plus in-memory group-by is simpler and cheaper than a paginated/joined query, and `StudyRepository` is already used this way elsewhere (the legacy worklist). Flagged, like Stage 84's own N+1, as a candidate for batching if a future stage's real usage pattern shows subject count growing large.

## 8. Auth (85-U)

No new auth annotation or gate. `AuthFilter` requires a valid bearer token for every path not on its small public whitelist (`/api/auth/*`, `/api/system/health`, demo-doctor when explicitly enabled) — `/api/subjects` (bare) automatically inherits this the same way `/api/subjects/{subjectRef}/history` already does. Verified live: an unauthenticated `GET /api/subjects` returns `401 AUTHENTICATION_REQUIRED`.

## 9. Pagination (85-J)

Not added. The Frontend list is unpaginated, matching the MVP's realistic scale (the same judgment call as §7); no existing pagination pattern was found elsewhere in this codebase for a comparable list to reuse, and inventing a new one for a handful of rows would be complexity without a caller.

## 10. Backend files new/modified

**New**: `dto/SubjectSummaryDto.java`, `service/SubjectListService.java`, `test/.../SubjectListServiceTest.java` (24 tests).

**Modified**: `controller/SubjectHistoryController.java` (+`SubjectListService` constructor dependency, +`GET /api/subjects` endpoint), `test/.../SubjectHistoryControllerTest.java` (constructor call site updated, +1 endpoint test).

## 11. Backend tests new / regression

25 new tests (24 in `SubjectListServiceTest`, 1 in `SubjectHistoryControllerTest`) — exceeds the 20+ target (85-V). Full `mvn test` (JDK21) regression: all pre-existing tests pass unchanged; this stage's constructor-signature change to `SubjectHistoryController` required updating exactly one call site (`SubjectHistoryControllerTest.buildController()`), the same kind of mechanical fix Stage 84's own `EffectiveMeasurementResolver` extraction required across several files.

## 12. What Stage 85 deliberately does not do

- No `Patient` entity, no migration, no Study schema change.
- No `/api/patients` endpoint of any kind — the old, broken route is left unimplemented on the Backend side; the Frontend fix (§ frontend doc) stops calling it instead.
- No batching/optimization of the per-subject effective-run query (§7) — not needed at today's scale, same judgment as Stage 84.
- No new auth gating, no pagination framework, no `warningCount` field (§4).

## 13. Next stage (86)

- If subject count grows large enough that the per-subject `EffectiveAnalysisRunSelector` calls (§7) become a real cost, revisit with a batched query joining Studies to their latest analysis run.
- Structured report/export (per the user's own proposed sequencing after this stage) is the next natural step now that the visible product flow (`Pacientes → Historial → Study → Análisis → Review → Evolución → Asistente`) is fully connected end to end.
