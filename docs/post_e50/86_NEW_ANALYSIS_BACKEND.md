# Stage 86 — New Analysis Flow / Patient Selector Cleanup (Backend)

Branch: `research/post-e50-spring-ai-product-contract` @ `f8cc5fb` (Stage 85). **Zero Backend changes in this stage.** Not committed/pushed as of this document (none needed).

## Patient entity: NOT PRESENT

No `Patient` table, no domain class, no FK — unchanged, same closed decision as Stages 84/85.

## Study ownership/identity: `Study.subjectRef`

Unchanged. What Stage 86 needed to confirm (86-A inventory) is that the Backend's existing Study-creation contract already accepts `subjectRef` directly, so no new endpoint or DTO field was required.

## Patient association endpoint: NOT USED

No such endpoint exists (`GET /api/patients`, `PUT /api/studies/{caseId}/patient` — neither has ever existed) and none was added. The Frontend's dependency on it was removed instead (see the Frontend doc).

## Legacy `/api/patients`: NOT USED BY NEW ANALYSIS FLOW

Confirmed: no controller anywhere maps `/api/patients` (grep across the whole codebase, same result as Stages 84/85).

## 86-A Inventory — the actual finding: the contract already existed

Before writing any code, this stage traced how a Study is really created today. `StudyController` has no `POST /api/studies` — Study creation happens as a side effect of the AI run request, in `MultiplanarRunPersistenceService.persistSuccessfulRun`:

```java
public void persistSuccessfulRun(MultiplanarRunRequestDto request, StudyMetadataDto studyMetadata, CanonicalMultiplanarRun response) {
    ...
    Study study = studyRunService.upsertStudyMetadata(request.caseId(), studyMetadata);
    ...
}
```

`studyMetadata` (a `StudyMetadataDto` with a `subjectRef` field, already validated by `StudyRunService.normalizeSubjectRef` — trim, `^[A-Za-z0-9._-]{3,64}$`, no `..`) is supplied by `AiMultiplanarController.run()`, which reads it straight from the HTTP request body (`MultiplanarRunApiRequestDto.studyMetadata()`). `POST /api/ai/multiplanar/run` — the endpoint `NewAnalysisDrawer.tsx` already calls to run an analysis — **already creates the Study with the caller-supplied `subjectRef` in the same request**, atomically from the Frontend's perspective. This is exactly the "Study creation accepts subjectRef directly, no post-association call" contract 86-G/86-I asked for — it was already built, just never wired to a working patient-selection UI on the Frontend.

## 86-H Validation — already reused, not duplicated

`StudyRunService.normalizeSubjectRef` (trim, regex, throws `INVALID_SUBJECT_REFERENCE` on failure) is the Backend-side rule. The Frontend's own `validateSubjectRef` (`src/studyMetadata.ts`) already mirrors it exactly and predates this stage — `SubjectSelector.tsx` (see Frontend doc) reuses that existing helper for its "new subject" manual-entry path, per 86-H's "no crear otra regla."

One pre-existing, minor duplication noted but **not touched** in this stage (out of scope — 86 is about the Frontend flow, not a Backend refactor): `StudyRunService.SUBJECT_REF_PATTERN` and the separately-extracted `SubjectReferenceValidator` (Stage 84, used by `PatientHistoryService`/`PatientMeasurementSeriesService`) implement the identical regex independently. Both were already in the codebase before this stage; unifying them would be a reasonable, small future cleanup but has no bearing on Stage 86's own scope.

## 86-U Auth

Unchanged. `POST /api/ai/multiplanar/run` and `GET /api/subjects` were already behind the same `AuthFilter` default (bearer token required) both before and after this stage.

## Migrations: 0

No schema change of any kind — the `subjectRef` column and its validation already existed.

## Why zero Backend files changed

Every 86-G/86-H/86-I/86-U requirement was already satisfied by code shipped before Stage 84. The actual gap Stage 86 closes is entirely on the Frontend: `NewAnalysisDrawer.tsx` was gating its whole flow on a broken `Patient`-selection step (`PatientSelector.tsx` → nonexistent `/api/patients`) instead of using the `subjectRef` field it was *already* sending in the same run request, and then making a second, also-broken call (`patientStudyAssociation.ts` → nonexistent `/api/studies/{caseId}/patient`) after the run succeeded. See `86_NEW_ANALYSIS_FRONTEND.md`.

## What Stage 86 deliberately does not do

- No `Patient` entity, no migration, no new endpoint, no DTO change.
- Does not unify `StudyRunService.SUBJECT_REF_PATTERN` with `SubjectReferenceValidator` (pre-existing minor duplication, out of scope).
- Does not touch the AI multiplanar pipeline itself, or the file-upload endpoints (`/api/ai/inputs`, `/api/ai/studies`) — per 86-P.

## A Docker-stack limitation found while testing (not a Backend defect)

While running the Stage-86 Docker E2E, `POST /api/ai/inputs` returned `502 UPSTREAM_UNAVAILABLE` against this repo's `docker-compose.post-e50.yml` stack. This is because that stack's `mock-ai` service (`docker/post-e50-mocks/mock-ai.mjs`) only implements the newer product-analysis pipeline (`POST /v1/product/analyze`), not the older multiplanar/upload pipeline (`POST /inputs`, `POST /multiplanar/run` or `/v2/multiplanar/run`) that `NewAnalysisDrawer.tsx` still calls. This is a pre-existing gap in the E2E mock infrastructure, unrelated to any Backend contract (the real Backend code path is correct and unchanged) and out of this stage's scope per 86-P ("no reescribir upload/DICOM ingestion"). See the general Stage-86 report for how this was worked around for the Docker/Browser E2E evidence, and the recommendation for Stage 87.
