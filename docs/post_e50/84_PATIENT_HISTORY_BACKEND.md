# Stage 84 — Patient History / Longitudinal Timeline via `subjectRef` (Backend)

Branch: `research/post-e50-spring-ai-product-contract`. Base commit `3cf1bb2` (Stage 82). Not committed/pushed as of this document. **Zero new migrations** — this stage runs entirely on the existing schema.

## 1. Architecture decision (closed, per the stage brief)

**No `Patient` entity, no FK, no Study schema change.** Longitudinal patient identity for this MVP is exactly `Study.subjectRef` (a nullable, de-identified string) — the same concept Stage 79 already used for its two-Study comparison. Stage 84 extends what already exists (`PatientHistoryService`, `SubjectHistoryController`, `EffectiveAnalysisRunSelector`, `LongitudinalComparisonService`) rather than inventing a parallel identity model.

## 2. Inventory (81-A-style) — what already existed, reused verbatim

- `GET /api/subjects/{subjectRef}/history` (`SubjectHistoryController`/`PatientHistoryService`) already existed and already worked — but summarized the **legacy** `domain_study_runs`/`StudyRun` model (pre-post-e50), never the post-e50 `AiProductAnalysisRun` model Stage 76/78/79/80 actually use. Extended additively (§3), not replaced.
- `EffectiveAnalysisRunSelector.selectEffectiveRun(studyId)` (Stage 79) — reused exactly, unmodified, for "which run represents this Study" in the new `productAnalysis` summary.
- The `(level, plane, structure, metric)` matching key and effective-value resolution (latest correction wins, else AI original) previously lived as **private** methods inside `LongitudinalComparisonService` — extracted into a new shared class (§4) rather than duplicated, per the stage's explicit "NO duplicar Stage 79."
- `StudyRepository.findStudiesBySubjectRef` — unchanged; its existing SQL (`WHERE subject_ref IS NOT NULL AND lower(subject_ref) = lower(?) ORDER BY study_date DESC NULLS LAST, created_at DESC`) already implements the exact normalization rule this stage requires (trim + case-insensitive, `null` never matches anything, including another `null`) — reused as-is, not reinvented.

## 3. History endpoint — additive `productAnalysis` field (84-B/84-C/84-D/84-G/84-H)

`PatientHistoryService.toHistoryStudy(study)` gained a new key, `productAnalysis`, built by `productAnalysisSummary(study)`:
```json
"productAnalysis": {
  "analysisRunId": "...", "status": "partial", "reviewStatus": "pending",
  "measurementCount": 1, "correctedMeasurementCount": 1,
  "warnings": ["67B2_RUNTIME_PARITY_NOT_PROVEN"],
  "humanReviewRequired": true, "notClinicalDiagnosis": true
}
```
— or `null` when the Study has no effective run yet (84-D: a normal state, not an error, and the Study is never hidden). Built via `EffectiveAnalysisRunSelector.selectEffectiveRun(study.id())` — the exact same rule Stage 79's comparison and Stage 80's chat already use, so a Study's "current" analysis is identical across history/comparison/chat, never a second divergent selection. `reviewStatus` comes from `AiProductAnalysisRunReview` (defaults to `"pending"` when no review row exists). `correctedMeasurementCount` counts **distinct** `measurementId`s with at least one correction (not correction rows — a measurement corrected twice still counts once). The legacy `StudyRun`-based fields already on this endpoint (`latestRunId`, `measurementsByPlane`, etc.) are untouched — this is purely additive.

## 4. `EffectiveMeasurementResolver` — extracted, not duplicated (84-C/84-J/84-L)

New class `service/EffectiveMeasurementResolver.java`, containing (moved verbatim from `LongitudinalComparisonService`): the `EffectiveMeasurement` record, `effectiveMeasurements(run)` (latest-correction-wins resolution), and a new `MatchKey` record (`level, plane, structure, metric`) plus `matchKey(measurement)`. `LongitudinalComparisonService` now takes this resolver as a constructor dependency and delegates to it — a pure extract-class refactor confirmed behavior-identical by its own unchanged 30-test suite (`LongitudinalComparisonServiceTest`) passing without modification to any assertion. This is the class the new N-Study series (§5) also depends on, so both Stage 79's two-Study comparison and Stage 84's N-Study series now share one single source of truth for "what does this run's effective measurement set look like."

## 5. Measurement series — N-Study evolution (84-I/84-K/84-L/84-M)

New service `PatientMeasurementSeriesService`, new DTO `MeasurementSeriesDto` (`key` + `points[]`, each point carrying `automaticValue`/`correctedValue`/`effectiveValue`/`effectiveSource` — the AI's original value is never omitted, matching Stage 78/79's "never overwrite" guarantee). Two new endpoints, same `/api/subjects/{subjectRef}/...` prefix as the existing history endpoint:

```
GET /api/subjects/{subjectRef}/measurement-series/keys
GET /api/subjects/{subjectRef}/measurement-series?level=&plane=&structure=&metric=
```

- `availableSeriesKeys`: iterates every Study's effective measurements, groups by `MatchKey`, and returns only keys with **≥2** comparable points (84-M) — a single-Study "series" isn't a trend.
- `series`: iterates the subject's Studies **chronologically ascending** (84-F: "para timeline visual: ASC cronológico" — the opposite order from the history-list endpoint's `studyDate DESC`), one point per Study for the requested key, `null`-level measurements always excluded (84-K: two unresolved-level measurements are never assumed to be the same anatomical structure).
- **Same-patient guard**: both new endpoints validate `subjectRef` via the extracted `SubjectReferenceValidator` (§6) and call `findStudiesBySubjectRef`, so a different patient's Studies structurally can never enter the aggregation — verified live in Docker E2E (§ hardening report) with `CASE-84-D` (a different `subjectRef`) never appearing in `PAT-84-1`'s series or history.

## 6. `SubjectReferenceValidator` — extracted, not duplicated

The subjectRef trim+format validation (`^[A-Za-z0-9._-]{3,64}$`, no `..`) previously lived as a private method inside `PatientHistoryService`. Extracted into `SubjectReferenceValidator.normalize(subjectRef)` (package-private, Stage-84-only consumers) so the two new measurement-series endpoints validate with the exact same rule instead of a second, possibly-diverging copy.

## 7. Ordering (84-F)

- History list: `studyDate DESC NULLS LAST, created_at DESC` — unchanged, already Stage-8E's own convention (`StudyRepository.findStudiesBySubjectRef`'s SQL).
- Measurement series: `studyDate ASC` (`Comparator.comparing(Study::studyDate, Comparator.nullsFirst(...)).thenComparing(Study::caseId)`) — a `null` studyDate sorts first, and a tie on the same date breaks deterministically by `caseId` (verified by two dedicated tests).

## 8. Auth

No new auth annotation — matches every other GET endpoint under `/api/subjects`/`/api/studies` in this codebase (unguarded by `RoleAuthorizationService.requireProfessional`, only side-effect audit logging via `ProfessionalAccessAuditService`, unchanged from the pre-existing `/history` endpoint's convention).

## 9. Privacy audit (84-W)

- `subjectRef` is never sent to the LLM (unaffected by this stage — Stage 80's `StudyChatContextBuilder` already excludes it structurally, confirmed still true, no code path added here reaches the chat context).
- `subjectRef` continues to appear in the `GET .../history` response body itself (legitimately needed — that's literally the resource being requested) and in `ProfessionalAccessAuditService`'s audit log message (pre-existing behavior, judged acceptable since it's a professional-access audit trail, not a general application log — not changed by this stage).
- No new logging statement in any Stage-84 class references `subjectRef`, question text, or measurement values beyond what the existing audit call already captured.

## 10. Performance (84-X)

For N Studies: 1 query for the Study list + up to N `selectEffectiveRun` calls (1 query each) + up to N pairs of measurement/correction queries for the effective run — the same N+1 shape Stage 79 already accepted for exactly 2 Studies, now scaled to N. Not optimized further in this stage (no batching/join added) since the realistic N for a single patient's imaging history is small (single digits), and no N+1 regression was observed in the Docker E2E timing for 3 Studies. Flagged as a candidate for batching if a future stage's usage pattern shows real N growing large.

## 11. Backend files new/modified

**New**: `service/EffectiveMeasurementResolver.java`, `service/PatientMeasurementSeriesService.java`, `service/SubjectReferenceValidator.java`, `dto/MeasurementSeriesDto.java`, `test/.../PatientMeasurementSeriesServiceTest.java`, `test/.../SubjectHistoryControllerTest.java`.

**Modified**: `service/PatientHistoryService.java` (+`productAnalysis` field, delegates subjectRef validation to the extracted validator), `service/LongitudinalComparisonService.java` (refactored to delegate to `EffectiveMeasurementResolver`, no behavior change), `controller/SubjectHistoryController.java` (+2 endpoints), `test/.../PatientHistoryServiceTest.java` (+11 tests), `test/.../LongitudinalComparisonServiceTest.java`/`LongitudinalComparisonControllerTest.java`/`ChatServiceTest.java`/`ChatControllerTest.java`/`StudyChatContextBuilderTest.java` (constructor call sites updated for the new `EffectiveMeasurementResolver` dependency, no assertion changes).

## 12. Backend tests new / regression

40 new tests (11 in `PatientHistoryServiceTest`, 24 in `PatientMeasurementSeriesServiceTest`, 5 in `SubjectHistoryControllerTest`) — exceeds the 30+ target. Full regression: see the general Stage-84 report for the exact final count (re-run after this document was drafted); the extraction refactor's own regression check (`LongitudinalComparisonServiceTest`'s pre-existing 30 tests) passed unchanged, confirming the refactor introduced no behavior change.

## 13. What Stage 84 deliberately does not do

- No `Patient` entity, no migration, no Study schema change (per the stage's own closed architecture decision).
- No batching/optimization of the N+1 query shape (§10) — not needed at today's realistic scale.
- No new auth gating — matches the existing convention for this endpoint family.
- No trend/progression computation — `MeasurementSeriesDto.Point` carries only raw values (automatic/corrected/effective), never a delta or clinical judgment (84-N); a consumer wanting a delta between two specific points still goes through Stage 79's `compare()`, unchanged.

## 14. Next stage (85)

- If a future stage needs faster N-Study aggregation for larger patient histories, revisit §10's per-run query pattern with a batched/joined query.
- Consider whether `PatientMeasurementSeriesService` should expose a "delta between two specific series points" convenience, though today the two-Study `LongitudinalComparisonService.compare()` already covers that need without duplication.
