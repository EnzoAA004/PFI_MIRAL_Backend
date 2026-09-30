# Stage 79 — Longitudinal comparison between Studies (Backend)

Branch: `research/post-e50-spring-ai-product-contract` (same branch as stages 75/76/78 — no new branch). Base commit `7cec356` (Stage 78). Not committed/pushed/merged as of this document.

## 1. Study/patient relationship found (79-A)

There is **no `Patient`/`Subject` entity anywhere in this domain** — confirmed by grepping `domain/`, `dto/`, and every migration under `docs/migrations/`. "Same patient" is modeled **only** as a de-identified free-text string, `Study.subjectRef` (`String`, nullable), with no foreign key and no uniqueness/format constraint at the DB level (`domain_studies.subject_ref TEXT NULL`, added by `V20260725_008_study_worklist_metadata.sql`; a non-unique, case-insensitive partial index exists — `V20260726_011_subject_history.sql` — whose own comment states *"Historical studies may keep subject_ref NULL; this index is intentionally non-unique"*).

The only existing code that already groups multiple Studies as one patient is `PatientHistoryService`/`StudyRepository.findStudiesBySubjectRef`, which matches **case-insensitively, trimmed**, and explicitly excludes any Study with a `null` `subjectRef` from ever matching anything (`subject_ref IS NOT NULL AND lower(subject_ref) = lower(?)` in Postgres; `study.subjectRef() != null && study.subjectRef().equalsIgnoreCase(normalized)` in-memory). No comparison/delta/baseline-vs-followup code existed anywhere before this stage (grepped `compar`, `longitudinal`, `delta`, `baseline`, `previous` — all unrelated hits, e.g. `baseline` only ever referred to the `real_baseline` AI inference mode, a different concept entirely).

**Decision**: stage 79 reuses this exact rule — case-insensitive, trimmed `subjectRef` equality — as the *only* safe evidence of "same patient." No new Patient concept, no new matching heuristic. Per the stage's explicit fail-closed instruction, a `Study` with a blank/`null` `subjectRef` can never be proven same-patient as anything (including another blank one), and this stage does not loosen that.

`Study.studyDate` (`LocalDate`, nullable) is used as-is for the comparison response's dates — never inferred from `analysis.createdAt` (79-M). The existing sort convention (`studyDate DESC NULLS LAST, createdAt DESC`, identical in both `PostgresStudyRepository` and `InMemoryStudyRepository`) was confirmed but not needed directly by this stage's endpoint, since the caller supplies both `caseId`s explicitly rather than asking the Backend to auto-pick a "previous Study" (see §11, auto-baseline was not built).

## 2. Effective value rule (79-B)

For each of a measurement's three fields (`areaMm2`/`majorAxisMm`/`minorAxisMm` — the only ones stage 76 persists):

- If a stage-78 `AiMeasurementCorrection` exists for that exact `measurementId`+`field` pair → `effectiveValue = correctedValue`, `source = "professional_correction"`.
- Otherwise → `effectiveValue = automaticValue` (read fresh from `AiMeasurementResult`), `source = "ai_original"`.

`automaticValue` is **always** included in the response regardless of which source is effective — the AI's original value is never omitted, matching stage 78's own "never overwrite" guarantee (this stage adds no new way to lose it).

## 3. Correction recency rule (79-O)

Corrections are append-only (stage 78). For a given `measurementId`+`field`, the correction with the latest `createdAt` wins — corrections are **never summed**. Implemented in `LongitudinalComparisonService.latestCorrectionsByMeasurementAndField`, a plain `HashMap` keyed by `measurementId + "|" + field`, keeping whichever candidate has the later `createdAt`.

## 4. Run selection policy (79-C) — `EffectiveAnalysisRunSelector`

No existing convention for "which run represents a Study" was found (greenfield). The explicit, documented rule:

1. Only `completed`/`partial` runs are ever eligible — `failed` is never usable, regardless of recency.
2. Among eligible runs, if at least one has a run-level review (stage 78) of `accepted` or `observed`, the most recent **such** run wins.
3. Otherwise, the most recent eligible run wins outright — including one that is `pending` or `rejected`. A `rejected` review does not remove a run from eligibility; rejecting a review is a professional judgment on quality, not a statement that no analysis exists.

This lives in its own component (`EffectiveAnalysisRunSelector`), exactly as the stage brief suggested ("Ideal: crear un selector centralizado"), reusable by any future feature needing "the one run that represents this Study."

## 5. Measurement matching key (79-E)

Two measurements are only ever compared when **all four** of `(level, plane, structure, metric)` match exactly — `areaMm2` is never compared against `majorAxisMm`, and `mm` is never compared against `mm²` (the unit is implied by `metric` and is fixed server-side, matching stage 78's own `unitFor`). A `level = null` measurement is **never** paired with anything, including another `level = null` entry — each renders as its own row with `comparisonStatus = "level_unresolved"`, independently from whichever side it came from. This is enforced structurally: `indexByKey` skips any measurement with a `null` level before building the lookup map used for pairing.

## 6. Delta formulas (79-F/79-H)

```
deltaAbsolute = current.effectiveValue - baseline.effectiveValue
deltaPercent  = baseline.effectiveValue == 0 ? null : (deltaAbsolute / abs(baseline.effectiveValue)) * 100
```

Division by zero never happens — `deltaPercent` is `null` instead. `trend` (`"increased"|"decreased"|"unchanged"`) is derived from `deltaAbsolute` alone, with a `1e-9` floating-point epsilon used **only** to treat two arithmetically-equal doubles as `"unchanged"` — this is a technical tolerance for floating-point representation, explicitly never a "clinical significance threshold" (79-H). No rounding happens server-side; the exact double is always returned, formatting is a Frontend concern.

## 7. Non-comparable cases (79-Q)

`comparisonStatus` per measurement row: `comparable | missing_baseline | missing_current | level_unresolved | unit_mismatch`. None of these ever produce an HTTP error — a Study pair with zero comparable measurements still returns `200` with an empty or partially-populated `measurements` array; this is data, not a failure (79-Q: "No lanzar 500 por diferencias normales de disponibilidad"). `unit_mismatch` is implemented defensively (kept for completeness) but is **structurally unreachable** today, since `metric` alone already determines `unit` server-side on both sides of every comparison — documented as such in the code rather than silently dropped.

Hard failures (the comparison cannot even be attempted) are the only cases that produce an HTTP error:
- Either `caseId` doesn't resolve to a Study → `StudyNotFoundException` (404, reused as-is).
- The two Studies don't share a `subjectRef` (or either is blank) → **also** `StudyNotFoundException` on the baseline `caseId` — deliberately reusing the existing cross-Study IDOR convention (stage 76/78: a resource belonging to a different Study 404s, never confirming it exists) rather than a `403`, so a baseline Study that exists but belongs to a different patient is indistinguishable from one that doesn't exist at all.
- `currentCaseId == baselineCaseId` → `400`, `LONGITUDINAL_SAME_STUDY`.
- Either Study has no usable run at all → `422`, `LONGITUDINAL_NO_USABLE_ANALYSIS_BASELINE`/`LONGITUDINAL_NO_USABLE_ANALYSIS_CURRENT` (two distinct codes so the Frontend can tell which side failed).

All four reuse `RunReviewException` (a plain `(HttpStatus, code, message)` exception already wired into `ApiExceptionHandler`'s generic handler) rather than a new exception class — consistent with the stage-78 decision to reuse it broadly instead of growing a parallel exception hierarchy. Three new `ApiErrorCode` catalog entries were added so `ApiErrorCode.fromCode(...)` resolves them to a real category/message instead of falling back to `UNKNOWN`.

## 8. Endpoint (79-K)

```
GET /api/studies/{caseId}/longitudinal-comparison?baselineCaseId=...
```

`caseId` in the path is the **current** Study; `baselineCaseId` is the Study to compare against. Both addressed by `caseId`, matching every other endpoint in this controller family (`/api/studies/{caseId}/...`) — the conceptual example in the stage brief used a raw `baselineStudyId` query param, adapted here to `baselineCaseId` for consistency with how every other endpoint in this API already addresses a Study. New controller `LongitudinalComparisonController` (a comparison *between* two Studies doesn't belong as a sub-resource of one product-analysis run, so it wasn't added to `AiProductAnalysisController`). Read-only and **not** gated by `RoleAuthorizationService.requireProfessional` — same convention as the existing GET history/detail endpoints (only stage-78's mutating PATCH/POST review endpoints require the professional role).

## 9. Persistence decision (79-J)

**Nothing is persisted.** The comparison is fully recomputed on every request from `AiProductAnalysisRun`/`AiMeasurementResult`/`AiMeasurementCorrection`/`AiProductAnalysisRunReview` — all already-persisted stage-76/78 data. This means a new correction made after a comparison was last viewed is reflected immediately on the next call, with zero migration and zero staleness risk. No new table, no new migration file for this stage.

## 10. DTOs / services / repositories

- `dto/LongitudinalComparisonResultDto.java` — top-level result plus nested `MeasurementComparison`/`EffectiveValue` records.
- `service/EffectiveAnalysisRunSelector.java` — the run-selection rule (§4), its own component.
- `service/LongitudinalComparisonService.java` — patient/study resolution, effective-value resolution, matching, delta math.
- `controller/LongitudinalComparisonController.java` — the single GET endpoint.
- **No new repository methods** — the feature is built entirely on `StudyRepository.findStudyByCaseId`, `findAiProductAnalysisRunsByStudyId`, `findAiProductAnalysisRunReview`, `findAiMeasurementResultsByAnalysisRunId`, `findAiMeasurementCorrectionsByAnalysisRunId`, all of which already existed from stages 76/78.
- **No new migration** (§9).

## 11. Limits / what stage 79 deliberately does not do

- No auto-baseline ("compare with previous Study") helper — the stage brief made this conditional ("sólo si ordenar Studies por fecha es confiable") and the caller-supplied `baselineCaseId` approach was judged sufficient and simpler for this pass; the sort convention needed for it already exists (`findStudiesBySubjectRef`) if a future stage wants to add it.
- Per-disc-level review status is not consulted by the run selector — only the run-level review (78-B), not 78-C's per-disc-level review, informs which run is "effective." Disc-level review granularity was judged unnecessary complexity for choosing a whole run.
- `unit_mismatch` is unreachable given current invariants (kept only for 79-Q completeness/future-proofing).
- No color/clinical-progression semantics anywhere in this layer — only `trend` (`increased`/`decreased`/`unchanged`, purely mathematical) and raw deltas; any presentation choice belongs to the Frontend (79-G/79-S).

## 12. Next stage (80)

- An auto-baseline helper ("compare with the immediately preceding Study") once/if a clinically-meaningful `studyDate` ordering guarantee is confirmed acceptable across the whole dataset, not just per-patient in isolation.
- Consider whether the per-disc-level review status (78-C) should ever influence run selection once real usage patterns are observed.
