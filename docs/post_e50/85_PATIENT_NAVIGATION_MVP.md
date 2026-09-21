# Stage 85 — Patient List / Navegación de Pacientes MVP

Backend: `research/post-e50-spring-ai-product-contract` @ `653b281` (+ Stage-85 uncommitted changes). Frontend: `research/post-e50-frontend-product-analysis` @ `78e850d` (+ Stage-85 uncommitted changes). Not committed/pushed/merged. `main` untouched. **Zero new migrations.**

## Patient entity: NOT PRESENT

No `Patient` table, no `Patient` domain class, no FK to any such entity exists anywhere in this codebase, and Stage 85 does not introduce one — the same closed decision Stage 84 made for patient history.

## Patient list: projection grouped by `Study.subjectRef`

The "Pacientes" screen the product needed was never a registry of Patient records — it is a read-time aggregate over `Study.subjectRef`: one row per distinct, non-null, normalized subjectRef, carrying only the count of Studies, the latest Study's date/caseId, and that latest Study's effective analysis/review status. `GET /api/subjects` (new, Backend) computes this on every request; nothing is persisted beyond the Studies that already exist.

## History: Stage 84

Clicking "Abrir historial" on any patient card opens exactly Stage 84's `PatientHistoryPage`/`PatientHistoryView` flow via `pathForPatient(subjectRef)` — unmodified. Stage 85 adds no second history mechanism.

## What was broken, and what Stage 85 closed

`PatientsView.tsx` called `searchPatients` → `GET /api/patients`, an endpoint that has never existed in the Backend (confirmed by grep across every controller, both at Stage 84 and re-confirmed here) — the `/pacientes` list has been non-functional since it was written, independent of any query typed into it. `PatientDetailView.tsx`, orphaned since Stage 84, was deleted outright (zero real consumers). The visible product flow is now fully connected end to end, non-mocked, for the first time:

```
Pacientes → Paciente/Historial → Study → Análisis → Review → Evolución → Asistente
```

verified live in both a real Docker stack and a real browser session (see the Backend/Frontend stage docs for the full step-by-step).

## A second, separate finding — explicitly not fixed here

While confirming `patientApi.ts`'s consumers before deciding what was safe to delete, this stage found that `src/features/worklist/PatientSelector.tsx` and `NewAnalysisDrawer.tsx` — the real, live "Nuevo análisis" patient-selection step — also call `createPatient`/`searchPatients`/`associateStudyPatient` against the same nonexistent `/api/patients`/`/api/studies/{caseId}/patient` endpoints. This is a second, independently broken surface, unrelated to `/pacientes`, and **out of this stage's scope**: fixing it would require either a real `Patient` entity (which this project's architecture explicitly rules out) or redesigning that flow's patient step around `subjectRef`, a decision with its own tradeoffs that deserves a dedicated stage rather than a side-effect of this one. `patientApi.ts` was therefore kept, unmodified — it is genuinely still needed by that flow, even though its `/pacientes`-facing usage has now been fully removed.

## Docker E2E result

Fixture: `PAT-85-A` (3 Studies, 2026-01-01/03-01/06-01), `PAT-85-B` (1 Study, 2026-02-15), `PAT-85-C` (2 Studies, 2026-04-01/07-15), plus `CASE-85-NULL` (a null-subjectRef Study) as a negative control. **Result: PASS** — `GET /api/subjects` returned exactly 3 rows, ordered `PAT-85-C, PAT-85-A, PAT-85-B` (`latestStudyDate DESC`), each with the correct `studyCount`/`latestCaseId`; the null-subjectRef Study never appeared; `GET /api/subjects/PAT-85-A/history` still returned all 3 of its Studies (Stage 84 unaffected); an unauthenticated request to `GET /api/subjects` was rejected with `401`.

## Browser E2E result

Real-backend session against the same Docker stack: `/pacientes` rendered 3 cards with the correct study counts, latest dates, and case IDs; searching by caseId (`CASE-85-B1`) filtered correctly to exactly one card; clicking "Abrir historial" opened Stage 84's real timeline for that subject (2 Studies, correct order); "Abrir estudio" opened the real Study at `/estudio/CASE-85-C2`; navigating back to `/pacientes` still rendered the same 3 cards; zero requests to `/api/patients` were observed across the entire session; the raw `subjectRef` (`PAT-85-A`/`PAT-85-B`/`PAT-85-C`) was confirmed absent from all visible page text on both the list and the history screen.

## Regression results

- **Backend** (JDK21, `mvn test`): full suite passes; 25 new tests (24 in `SubjectListServiceTest`, 1 in `SubjectHistoryControllerTest`).
- **Frontend** (`npm test`, 55 scripts): exit 0, no failures (24 new tests in `post-e50-patient-list-tests.mjs`; 3 pre-existing stale tests updated to reflect the corrected architecture, not weakened).
- **Frontend typecheck** (`npx tsc -b`): clean.
- **Frontend lint** (`npx eslint src`): clean.
- **Frontend production build** (`npm run build`): succeeds.
- **Migrations**: 0 new — confirmed by `git status` on both repos.

## Known limitations

- The "Nuevo análisis" patient-selection flow's own `/api/patients` dependency (§ above) remains broken — a separate, pre-existing gap, out of this stage's scope, documented for a future stage.
- `SubjectListService`'s per-subject effective-run lookup is an N+1-per-subject query shape (documented, not optimized) — acceptable at today's realistic scale, same judgment Stage 84 already made for its own per-Study N+1.
- No pagination on the list — acceptable at today's realistic scale.

## Release status

**PATIENT_NAVIGATION_MVP_READY_WITH_LIMITATIONS**

Every item in this stage's own Definition of Done that falls within its stated scope was met: `/pacientes` works against real data, the list is a subjectRef projection with no Patient entity, null subjectRef is excluded, normalized grouping works, latest-Study/effective-analysis/review-status selection are all correct and reuse Stage 79/84's existing rules, the Frontend no longer calls any nonexistent endpoint from this screen, navigation reaches Stage 84's history/Study/Review/Evolución/Asistente flow, both E2E layers pass, no unnecessary privacy leak was found, both regressions pass, and zero migrations were added. The "with limitations" qualifier reflects the newly-discovered, explicitly out-of-scope "Nuevo análisis" patient-selection gap (§ above) — a real limitation of the product today, not a regression introduced by this stage, and not silently hidden.

## Recommendation for Stage 86

Two candidates, in the user's own previously-stated preference order: (a) continue to structured report/export now that the visible flow is fully connected, or (b) first resolve the newly-discovered "Nuevo análisis" patient-selection gap (§ above), since it blocks a real, live core feature (creating an analysis against a genuinely new or existing patient) rather than a secondary screen. Both are zero-additional-cloud-cost paths.

NO COMMIT. NO PUSH. NO MERGE. NO TOCAR MAIN.
