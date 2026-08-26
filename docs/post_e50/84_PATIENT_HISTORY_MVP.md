# Stage 84 — Patient History / Longitudinal Timeline MVP

Backend: `research/post-e50-spring-ai-product-contract` @ `3cf1bb2` (+ Stage-84 uncommitted changes). Frontend: `research/post-e50-frontend-product-analysis` @ `bd4c281` (+ Stage-84 uncommitted changes). Not committed/pushed/merged. `main` untouched. **Zero new migrations.**

## Patient entity: NOT PRESENT

No `Patient` table, no `Patient` domain class, no FK to any such entity exists anywhere in this codebase, and Stage 84 does not introduce one. This is a closed architecture decision, not an oversight.

## Longitudinal identity: `Study.subjectRef`

For this MVP, "same patient" = two Studies whose `subjectRef` values are equal after normalization. There is no other notion of patient identity anywhere in the domain model.

## Normalization rule (exact)

Trim, then case-insensitive comparison — implemented once, in `StudyRepository.findStudiesBySubjectRef`'s SQL (`WHERE subject_ref IS NOT NULL AND lower(subject_ref) = lower(?)`) and mirrored in `InMemoryStudyRepository` (`study.subjectRef().equalsIgnoreCase(normalized)`). Stage 84 reuses this exact rule everywhere (history, both new measurement-series endpoints) — no second normalization was written.

## Null `subjectRef`: not longitudinally identifiable

A Study with `subjectRef = null` can be opened individually and can have its own analysis, but it never groups with any other Study — including another Study that is also `null`. This is enforced structurally: the SQL/in-memory matching both explicitly require a non-null `subjectRef` on both sides before considering two Studies "the same patient." Verified live in Docker E2E with a dedicated null-subjectRef Study that never appeared in any other subject's history.

## Future production evolution

If this product moves beyond MVP into a setting requiring a real institutional patient identity (e.g., integration with a hospital's MPI/master patient index), `subjectRef` can be replaced by a proper `Patient` entity at that point without disturbing this stage's API shape — `GET /api/subjects/{subjectRef}/history` and the two measurement-series endpoints would simply resolve `{subjectRef}` against the new entity's identifier instead of the raw Study column. This is a deliberately deferred decision, not a limitation being hidden.

## What was built

**Backend**: `productAnalysis` summary added (additively) to the existing `/api/subjects/{subjectRef}/history` endpoint, reusing `EffectiveAnalysisRunSelector` (Stage 79) for run selection; two new endpoints for N-Study measurement series (`.../measurement-series` and `.../measurement-series/keys`), built on a newly-extracted `EffectiveMeasurementResolver` shared with Stage 79's own two-Study comparison (a pure refactor, confirmed behavior-identical by Stage 79's own unchanged 30-test suite). See `84_PATIENT_HISTORY_BACKEND.md`.

**Frontend**: discovered and fixed a real routing bug — `/pacientes/:id` was wired to a component (`PatientDetailView`) calling two Backend endpoints that never existed. Replaced with a real connection of the already-built, already-tested `PatientHistoryView` (via a new `PatientHistoryPage` wrapper), evolved with: a privacy fix (no longer prints the raw `subjectRef`), the new `productAnalysis` summary per Study, a "current Study" marker, a "Comparar" action reusing Stage 79's `LongitudinalComparisonPanel` unchanged, and a real N-Study "Evolución de mediciones" view (table, no new chart library). See `84_PATIENT_HISTORY_FRONTEND.md`.

## Docker E2E result

Fixture: `subjectRef=PAT-84-1` with Studies A (2026-02-01), B (2026-05-10), C (2026-08-25, with a professional correction on `majorAxisMm`), plus `CASE-84-D` (different subjectRef `PAT-84-OTHER`) and a null-subjectRef Study as negative controls. **Result: PASS** — history returns exactly 3 Studies in `C, B, A` order; the series for `L4-L5/sagittal/disc/majorAxisMm` returns exactly 3 points in `A, B, C` order with the corrected point's original AI value preserved (`effectiveSource: professional_correction`); `CASE-84-D` and the null-subjectRef Study never appear in `PAT-84-1`'s data; comparing C against B via the existing Stage-79 endpoint returns the expected `deltaAbsolute≈0.6`, unchanged.

## Browser E2E result

Real-backend Playwright script (`playwright-post-e50-patient-history-e2e.mjs`), extending Stage 81's pattern — **9/9 checks PASS**, including a genuine, verified confirmation that the raw `subjectRef` is never present in the rendered page text (a real privacy check, not just a code-review assertion), and the "Comparar" action correctly opening the reused Stage-79 comparison panel from the timeline. One real bug was found and fixed during this run (§ frontend doc §6: the evolution selector showed a raw metric field name instead of a human label because the wrong existing helper was used) — a genuine value of actually exercising the browser, not just unit tests.

## Regression results

- **Backend** (JDK21, `mvn test`): 851 tests, 0 failures, 0 errors, 1 skipped (40 new: 11 in `PatientHistoryServiceTest`, 24 in `PatientMeasurementSeriesServiceTest`, 5 in `SubjectHistoryControllerTest`).
- **Frontend** (`npm test`): exit 0, no failures (31 new tests in `post-e50-patient-history-tests.mjs`).
- **Frontend typecheck** (`npx tsc -b`): clean.
- **Frontend lint** (`npx eslint src`): clean.
- **Frontend production build** (`npm run build`): succeeds.
- **Migrations**: 0 new — confirmed by inspection of `git status` (no new file under `docs/migrations/`).

## Known limitations

- The `/pacientes` list page remains broken (calls a still-nonexistent `GET /api/patients`) — a separate, pre-existing gap not in this stage's scope (entry point was from an open Study, per the stage brief's own UX goal), documented for a future stage.
- `PatientDetailView.tsx`/`patientApi.ts` remain in the codebase as orphaned, unused files (not deleted, per the project's standing "don't delete without being asked" practice) — a future cleanup candidate.
- N+1 query shape for the history/series endpoints scales linearly with Study count per patient — acceptable at today's realistic scale (single digits), not batched/optimized further.

## Release status

**PATIENT_HISTORY_MVP_READY**

Every item in the stage's own Definition of Done was met: `subjectRef` is the longitudinal identity, no `Patient` entity was created, history returns N Studies with the current Study identifiable, analysis/review/correction/warning summaries are visible, a Study without analysis degrades gracefully, the N-Study series works with corrected-value priority and original-value preservation, `null`-level measurements are never paired, the same-subjectRef guard and different-subjectRef exclusion were both verified live in Docker, the Frontend timeline and evolution view both work end-to-end, "Comparar" reuses Stage 79 unchanged, the browser smoke passed 9/9, both regressions pass, zero migrations were added, and `main` is untouched. The known limitations above are pre-existing or explicitly out-of-scope items, not regressions introduced by this stage.

## Recommendation for Stage 85

Either (a) fix the still-broken `/pacientes` list page (§ known limitations) so patient discovery doesn't depend on already knowing a `subjectRef` from an open Study, or (b) move toward the presentation/documentation deliverables discussed after Stage 82 (architecture diagram, limitations/threats writeup, demo checklist) — both are zero-additional-cloud-cost paths that continue improving the product/thesis without reopening the AI research, GPU, or training surfaces this project has consistently kept out of scope.
