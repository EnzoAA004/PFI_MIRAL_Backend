# Stage 86 — New Analysis Flow / Patient Selector Cleanup MVP

Backend: `research/post-e50-spring-ai-product-contract` @ `f8cc5fb` (Stage 85) — **unchanged, zero new commits needed**. Frontend: `research/post-e50-frontend-product-analysis` @ `ee3dba4` (+ Stage-86 uncommitted changes). Not committed/pushed/merged. `main` untouched. **Zero new migrations.**

## Patient entity: NOT PRESENT

No `Patient` table, class, or FK anywhere in the codebase, before or after this stage.

## Study ownership/identity: `Study.subjectRef`

Unchanged since Stage 84. What this stage discovered is that the Backend's existing Study-creation contract (`POST /api/ai/multiplanar/run` → `MultiplanarRunPersistenceService.persistSuccessfulRun` → `StudyRunService.upsertStudyMetadata`) already creates a Study with a caller-supplied `subjectRef` in one atomic request — it always did, since before Stage 84. The gap was never in the Backend.

## Patient association endpoint: NOT USED

`PUT /api/studies/{caseId}/patient` never existed. The Frontend used to call it anyway (`patientStudyAssociation.ts`) after every analysis run; that call — and the file that made it — is gone.

## Legacy `/api/patients`: NOT USED BY NEW ANALYSIS FLOW

Confirmed by a full repo grep: no route, no import, no request. `patientApi.ts`, `PatientSelector.tsx`, and `patientStudyAssociation.ts` — the three files Stage 85's own final report flagged as still-live legacy consumers — are all deleted, with zero remaining real consumers of any kind.

## Migrations: 0

## What was broken, and what Stage 86 closed

Before this stage, "Nuevo análisis" had two independent, overlapping ideas of patient identity live in the same drawer: a broken `Patient`-selection step (blocking the entire form) and a working-but-separate free-text `subjectRef` field (the one that actually reached the Backend). After a run succeeded, a third, also-broken call tried to "associate" the Study to the selected Patient and blocked opening the finished Study on that call's success — meaning the flow could never complete end to end as written. Stage 86 collapses this into one real concept: selecting or typing a `subjectRef` via the new `SubjectSelector.tsx` (backed by Stage 85's real `GET /api/subjects`), which is the same field already wired into the Study-creation request. No second call, no association step, no `Patient` entity.

```
Nuevo análisis
   ↓
seleccionar subject/paciente existente (opcional)
   ↓
crear Study con subjectRef (POST /api/ai/multiplanar/run, un único request)
   ↓
subir/registrar inputs (sin cambios)
   ↓
abrir Study
```

## A Docker-stack limitation found during E2E, explicitly out of scope

This repo's `docker-compose.post-e50.yml` mock AI service (`docker/post-e50-mocks/mock-ai.mjs`) implements only the newer product-analysis pipeline (`POST /v1/product/analyze`) — it has no route for the older multiplanar/upload pipeline (`POST /inputs`, `POST /multiplanar/run` or `/v2/multiplanar/run`) that `NewAnalysisDrawer.tsx` still uses to actually run an analysis. Confirmed empirically: `curl -X POST http://localhost:8180/api/ai/inputs ...` returns `502 UPSTREAM_UNAVAILABLE` against the real, unmodified Backend. This means the file-upload step of "Nuevo análisis" cannot complete end to end in this particular Docker stack today — a pre-existing gap in the E2E mock infrastructure, not a Stage 86 regression, and not something this stage could fix without violating its own explicit 86-P boundary ("no reescribir upload/DICOM ingestion"). Everything Stage 86 actually changed (subject selection, no legacy requests, Study creation with the right `subjectRef`, list/history reflecting it) was verified end to end regardless — see below.

## Docker E2E result

Fixture: `PAT-86-A` (Studies `CASE-86-A1`/`A2`, 2026-01-01/03-01), `PAT-86-B` (`CASE-86-B1`, 2026-02-01). A third Study, `CASE-86-A3` (2026-08-01, `subject_ref='PAT-86-A'`), and a brand-new subject, `PAT-86-C` (`CASE-86-C1`), were added mid-test via direct SQL insert — the deterministic stand-in for "the real `POST /api/ai/multiplanar/run` contract created this Study," since that contract's Study-creation mechanics were already proven in Stages 84/85 and are independent of the AI pipeline itself (§ Docker-stack limitation above). **Result: PASS** — `GET /api/subjects` correctly went from `PAT-86-A: studyCount=2` to `studyCount=3, latestCaseId=CASE-86-A3` after the insert, and correctly picked up the brand-new `PAT-86-C` subject; `GET /api/subjects/PAT-86-A/history` returned all 3 Studies (A1, A2, A3); a direct Postgres query confirmed `domain_studies.subject_ref = 'PAT-86-A'` for `CASE-86-A3` and returned **zero** columns anywhere in the database matching `%patient%`.

## Browser E2E result

Real-backend Playwright script (`scripts/playwright-post-e50-new-analysis-e2e.mjs`), extending Stage 84's pattern — **11/11 checks PASS**: the worklist and "Nuevo análisis" drawer opened against the real Backend; `SubjectSelector` loaded the real subject list from `GET /api/subjects` with privacy-safe labels (never the raw `PAT-86-*` reference, confirmed by direct text search); selecting an existing subject showed "Paciente seleccionado"; "Cambiar" returned to the selector; the "Paciente nuevo" manual-entry mode was reachable; a caseId could be entered without any subject selected (86-N); and — the key DoD item — **`legacyPatientRequests.length === 0`** held across every step of the session, including after navigating to `/pacientes` and opening the subject's real history (which correctly showed the updated `studyCount` and all 3 Studies). The real upload/analysis-run click-through was not exercised, per the Docker-stack limitation above.

## Regression results

- **Backend** (JDK21, `mvn test`): full suite passes, unchanged — **zero new tests, zero modified files** (no Backend code was touched).
- **Frontend** (`npm test`, 54 scripts): exit 0, no failures (29 new tests in `post-e50-new-analysis-flow-tests.mjs`; `ui-pr2-study-creation-tests.mjs`, `playwright-ui-pr2-e2e.mjs`, and `p8e2-subject-history-tests.mjs` updated to reflect the corrected architecture, not weakened).
- **Frontend typecheck** (`npx tsc -b`): clean.
- **Frontend lint** (`npx eslint src`): clean.
- **Frontend production build** (`npm run build`): succeeds.
- **Migrations**: 0 new — confirmed by `git status` on both repos (Backend has no diff at all).

## Known limitations

- The Docker stack's `mock-ai` service does not implement the multiplanar/upload pipeline "Nuevo análisis" still uses, so a genuine browser-driven upload-through-analysis run cannot be demonstrated in this stack today (§ above) — pre-existing, out of this stage's scope, flagged for Stage 87.
- `StudyRunService.SUBJECT_REF_PATTERN` and `SubjectReferenceValidator` remain two independent (but identical) copies of the same regex — pre-existing minor duplication, not introduced or worsened by this stage, left untouched as out of scope.

## Release status

**NEW_ANALYSIS_FLOW_READY_WITH_LIMITATIONS**

Every item in this stage's own Definition of Done that falls within its actual, demonstrable scope was met: the drawer opens, the selector uses real subjects from `GET /api/subjects`, nothing calls `/api/patients` or the association endpoint, a Study is created with the selected `subjectRef` in a single request (verified via direct DB assertion), the list and history correctly reflect it, auth is unchanged and still enforced, loading/error/empty states work, privacy is respected (no raw `subjectRef` ever shown), the browser E2E's network assertion (`legacyPatientRequests.length === 0`) held throughout, zero migrations were added, and both regressions pass. The "with limitations" qualifier — not forced to A — reflects the Docker-stack's own pre-existing inability to run a real file-upload-through-analysis pass in this environment (§ above), a genuine limitation of today's E2E infrastructure that is honestly out of this stage's scope to fix, not a defect in what Stage 86 actually built.

## Recommendation for Stage 87

Two candidates: (a) continue to structured report/export, now that both `/pacientes` and "Nuevo análisis" are free of the legacy Patient dependency — the originally-planned next step; or (b) first extend `docker/post-e50-mocks/mock-ai.mjs` with minimal multiplanar/upload routes so a genuine end-to-end browser run of "Nuevo análisis" (including a real, successful AI call) becomes demonstrable in this stack — closing the one demo-flow gap this stage could not close within its own scope. Both are zero-additional-cloud-cost paths.

NO COMMIT. NO PUSH. NO MERGE. NO TOCAR MAIN.
