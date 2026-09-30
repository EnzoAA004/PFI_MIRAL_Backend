# Stage 78 — Professional review/correction workflow (Backend)

Branch: `research/post-e50-spring-ai-product-contract` (same branch as stages 75/76 — no new branch). Not committed/pushed/merged as of this document.

## 1. Existing review infrastructure found (78-A)

A full review workflow already exists for **multiplanar runs** (`domain_study_runs`), pre-dating stage 75/76's AI-product-analysis persistence:

- `domain/RunReview.java` — `(multiplanarRunId, traceId, reviewStatus, reviewer, reviewedAt, comments, corrections)`. Review data lives directly as columns on `domain_study_runs` (`review_status, reviewer, reviewed_at, comments`), not a separate table.
- `domain/MeasurementCorrection.java` — `(id, studyRunId, measurementId, label, beforeValue, afterValue, comment, createdAt)`, `beforeValue`/`afterValue` as JSONB blobs. Table `domain_review_corrections`, replace-on-save semantics (delete-then-insert per review call).
- `domain/ReviewerAnnotation.java` — geometry/text the reviewer authored themselves (no AI value pair) — confirmed **not** reusable for measurement corrections, a distinct concept.
- `service/RunReviewService.java` + `controller/AiRunReviewController.java` — `POST`/`PUT`/`GET /api/ai/runs/{multiplanarRunId}/review`. Status vocabulary `pending|accepted|observed|rejected|edited`, with a Spanish-synonym normalizer. Validates: non-blank `reviewer` required for any non-pending status; a comment of ≥5 chars required for `observed`/`rejected`. Throws `RunReviewException(HttpStatus, code, message)` — a **generic, reusable** exception, not multiplanar-specific despite its name.
- `service/ReviewStatusMapper.java` — Spanish (UI) ↔ English (DB) status translation, specific to the older UI's vocabulary.
- Reviewer identity: a real JWT-derived principal exists (`auth/TokenService.Claims(subject, email, name, roles)`, set as a request attribute by `AuthFilter.AUTH_CLAIMS_ATTRIBUTE`), and `auth/RoleAuthorizationService.requireProfessional(request, entityId)` already gates professional actions to `REVIEWER|DOCTOR|ADMIN` roles. However, the **existing** `RunReviewService` does not use it for `reviewer` — it trusts a client-supplied string instead. Stage 78 does not repeat that gap (see §7).
- No optimistic locking/version column anywhere in the schema (confirmed by grep) — concurrent writes are last-write-wins today.

**Decision**: reuse `RunReviewException` as-is (it's a plain `(HttpStatus, code, message)` exception with no multiplanar-specific fields — reusing it means the existing generic `ApiExceptionHandler` handler needs zero changes). Reuse `AuditService.record(...)` for change history instead of inventing an event log. Reuse `RoleAuthorizationService.requireProfessional`. **Do not** reuse `RunReview`/`MeasurementCorrection`/`ReviewStatusMapper` themselves — those are scoped to `domain_study_runs` (multiplanar runs), a different resource family from `AiProductAnalysisRun` (stage 76). Building new, narrowly-scoped tables/records for the AI-product-analysis family avoids overloading one review model across two different persistence shapes, while reusing every reusable *pattern* (manual-transaction style, upsert-not-append for the "current state" row, `AuditService` for history, `RunReviewException` for errors).

## 2. Review model (final)

Two new upserted (one-row-per-target) tables, plus one append-only table:

- **`domain_ai_analysis_run_reviews`** (78-B) — one row per `AiProductAnalysisRun`: `review_status` (`pending|accepted|observed|rejected` — no `edited`, and no clinical vocabulary), `review_comment`, `reviewed_by`, `reviewed_at`, `updated_at`. `UNIQUE(analysis_run_id)` — `INSERT ... ON CONFLICT (analysis_run_id) DO UPDATE`, so repeated reviews (`pending→accepted→observed`) update the same row deterministically rather than growing a table. Change history is covered by `domain_audit_events` (`action = "ai_product_review.run_status_changed"`), not a bespoke audit table.
- **`domain_ai_disc_level_reviews`** (78-C) — identical shape, one row per `AiDiscLevelResult`. The minimal granular layer defensible beyond run-level review, per the "no sobre-normalizar" instruction — no per-measurement review status was added (only per-measurement *correction*, which is the mechanism that actually matters at that granularity).
- **`domain_ai_measurement_corrections`** (78-D) — **append-only**, no unique constraint: `measurement_id`, `analysis_run_id`, `field` (`areaMm2|majorAxisMm|minorAxisMm` — the only numeric fields this product exposes), `original_value` (snapshot at correction time), `corrected_value`, `unit` (server-derived, never client-supplied), `comment`, `reviewed_by`, `created_at`. `domain_ai_measurement_results` is **never** `UPDATE`d — the "current" corrected value for a field is simply the latest correction row by `created_at`.

New domain records: `AiProductAnalysisRunReview`, `AiDiscLevelReview`, `AiMeasurementCorrection` — all plain records, matching the codebase's non-JPA style.

## 3. Migration

`docs/migrations/V20260825_017_ai_product_analysis_review.sql` — 3 new tables, nothing altered/dropped. `CHECK (review_status IN ('pending','accepted','observed','rejected'))` on both review tables; `CHECK (field IN ('areaMm2','majorAxisMm','minorAxisMm'))` on corrections. Foreign keys `ON DELETE CASCADE` to `domain_ai_analysis_runs`/`domain_ai_disc_level_results`/`domain_ai_measurement_results` — deleting an analysis run removes its reviews/corrections too. Indexes: `measurement_id`, `analysis_run_id` on the corrections table (the review tables' `UNIQUE` constraints already provide an index on their FK column).

## 4. Repository

7 new `StudyRepository` methods, implemented identically in `PostgresStudyRepository` (Postgres `INSERT ... ON CONFLICT DO UPDATE ... RETURNING` for the two upserted tables; plain `INSERT` for corrections) and `InMemoryStudyRepository` (`ConcurrentHashMap`, same upsert-vs-append semantics reproduced with `put`/`compute`):

```java
AiProductAnalysisRunReview upsertAiProductAnalysisRunReview(String analysisRunId, String reviewStatus, String reviewComment, String reviewedBy, Instant reviewedAt);
Optional<AiProductAnalysisRunReview> findAiProductAnalysisRunReview(String analysisRunId);
AiDiscLevelReview upsertAiDiscLevelReview(String discLevelResultId, String reviewStatus, String reviewComment, String reviewedBy, Instant reviewedAt);
Optional<AiDiscLevelReview> findAiDiscLevelReview(String discLevelResultId);
List<AiDiscLevelReview> findAiDiscLevelReviewsByAnalysisRunId(String analysisRunId);
AiMeasurementCorrection saveAiMeasurementCorrection(AiMeasurementCorrection correction);
List<AiMeasurementCorrection> findAiMeasurementCorrectionsByAnalysisRunId(String analysisRunId);
```

## 5. Service — `AiProductAnalysisReviewService`

`reviewRun`, `reviewDiscLevel`, `correctMeasurement` — each: resolve+IDOR-check the target via `AiProductAnalysisPersistenceService.resolveAnalysisRun(caseId, analysisId)` (shared with the existing GET-detail 404 rule, not a second copy of it) → validate → write to the stage-78 tables only → write a `DomainAuditEvent` via `AuditService.record(...)` → **re-read** the full analysis through `AiProductAnalysisPersistenceService.detail(...)` and return that, so every response (POST/GET/PATCH) is built from the exact same code path.

**No AI call happens anywhere in this class** (78-N) — review is a pure Backend/DB operation, and `analysisService`/`AiServiceOperations` are not even dependencies of this service.

Validation:
- `status` ∈ `{pending, accepted, observed, rejected}` (case-insensitive) — else `RunReviewException(400, "AI_PRODUCT_REVIEW_INVALID_STATUS", ...)`.
- `comment` optional; trimmed; rejected if `> 2000` chars or contains `<`/`>` (a deliberately blunt but effective plain-text guard — "no permitir HTML arbitrario / XSS persistente") — `RunReviewException(400, "AI_PRODUCT_REVIEW_COMMENT_INVALID", ...)`.
- correction `field` ∈ `{areaMm2, majorAxisMm, minorAxisMm}` else `RunReviewException(400, "AI_PRODUCT_CORRECTION_INVALID_VALUE", ...)`.
- correction `correctedValue`: `Double.isFinite(...)` — rejects `null`, `NaN`, and `±Infinity` with the same code.
- `unit` is **never** read from the request — `unitFor(field)` derives `"mm2"` for `areaMm2` and `"mm"` for the two axis fields, so a client cannot relabel a value's unit.
- Cross-Study/cross-analysis IDOR: an `analysisId` from another Study, a `discLevelId`/`measurementId` from another analysis run — all 404 via `ResponseStatusException`, same as the existing GET-detail behavior; existence is never leaked.

Errors wrap repository `IllegalStateException` into `DatabaseUnavailableException` (503), matching the rest of the codebase.

## 6. Endpoints

```
PATCH  /api/studies/{caseId}/product-analyses/{analysisId}/review
PATCH  /api/studies/{caseId}/product-analyses/{analysisId}/disc-levels/{discLevelId}/review
POST   /api/studies/{caseId}/product-analyses/{analysisId}/measurements/{measurementId}/corrections
```

All three added to the existing `AiProductAnalysisController` (no new controller — this is the same sub-resource family as the stage-76 POST/GET endpoints). All three call `RoleAuthorizationService.requireProfessional(...)` before doing anything else — a non-professional caller gets `403` and never reaches the service layer.

Request bodies: `AiProductAnalysisReviewRequestDto(status, comment)` for the two review endpoints; `AiMeasurementCorrectionRequestDto(field, correctedValue, comment)` for the correction endpoint. **None of these accept a reviewer/reviewedBy field** — see §7.

## 7. Reviewer identity (78: "no inventar nombre de médico")

`reviewedBy` is derived **only** from the authenticated principal's own JWT claims (`AuthFilter.AUTH_CLAIMS_ATTRIBUTE` → `TokenService.Claims.email()`, falling back to `.subject()`), read directly in the controller — never trusted from the request body. If no authenticated claims are present, `reviewedBy` is `null` (a real, persisted `NULL`, not a placeholder string like `"Admin"`/`"Dr. Enzo"`). This is a deliberate improvement over the pre-existing multiplanar `RunReviewService`, which does trust a client-supplied `reviewer` string — that legacy endpoint was left untouched (out of scope for this stage) rather than silently changed.

## 8. Response shape

`AiProductAnalysisResultDto` (stage 76) extended, not replaced:
- `DiscLevelView`/`MeasurementView` now expose `discLevelId`/`measurementId` — opaque Backend-generated ids the Frontend needs to address the new endpoints (no clinical/infrastructure meaning, unlike the already-excluded AI base URL/inputIds/model SHA).
- `ReviewView` gained `comment`/`reviewedBy`/`reviewedAt`, and is now reused unchanged for both the run's own review and each disc level's review (identical shape, identical semantics).
- `MeasurementView` gained `corrections: List<MeasurementCorrectionView>` — one entry per corrected `field`, each carrying `originalValue` (the AI's own value, always present) **alongside** `correctedValue` — the AI value is never hidden or overwritten in the response, exactly mirroring the DB.

A run/disc-level with no review yet renders `status: "pending"` (never an error, never an omitted section) — `AiProductAnalysisMapper.toResultDto` treats a `null` review record as an implicit pending default.

## 9. 67B2 / 67C / P10.7 — review is strictly additive

Nothing in `AiProductAnalysisReviewService` or the mapper writes to `AiDiscLevelResult`'s own fields. Verified explicitly by test:
- **67C**: `reviewDiscLevel(..., "accepted", ...)` on a pairing with `axialPairingValidated=false`/`axialPairingExperimental=true` leaves both fields exactly as they were — `acceptingSixtySevenCPairingDoesNotSetValidatedTrue`. The AI method's own validation status and the professional's review decision are stored in entirely separate tables; there is no code path that could conflate them.
- **P10.7**: accepting/observing/rejecting a disc level whose `degenerativeFindingsStatus = "blocked_preprocessing_parity"` leaves that field untouched — `blockedP10_7NeverBecomesANegativeFindingAfterReview`. There is no "finding" for the reviewer to accept/reject in the first place; only the disc-level's *review status* changes, never its AI-reported capability status.
- **67B2**: no "manual level assignment" (`professionalAssignedLevel`) was implemented in this stage — deliberately deferred (see §12) since it would require a new field distinguishing AI-asserted vs. professional-asserted levels, which the stage instructions explicitly flagged as scope-expanding.

## 10. Docker

Docker Desktop was available and used for real Postgres integration tests this stage (previously skipped in earlier stages due to no Docker). See the final report for exact `docker version`/results.

## 11. Tests

52 new backend tests across 3 files (25 items from the 78-P checklist all covered, several more than once at different layers):

- `AiProductAnalysisReviewServiceTest` (25) — pending-initial, accept/observe/reject persistence, comment persistence, invalid status, cross-Study 404, nonexistent-analysis 404, disc-level review independent of run review, cross-analysis disc-level 404, correction persisted, original value preserved, corrected value visible alongside original, NaN/Infinity rejected, unit always server-derived, 67C accepted ≠ validated=true, P10.7 blocked stays blocked, review survives repeated reads, multiple runs independent, XSS-shaped comment rejected (not persisted), invalid correction leaves no partial state, no AI call during review/correction, nullable reviewer, GET returns the review.
- `AiProductAnalysisControllerTest` (+7 new, 13 total) — 403 without a professional role, accepted review visible via the controller with `reviewedBy` derived from claims, invalid status still throws at the controller layer, disc-level review doesn't touch run review (plus 67C fields untouched), correction round-trip through the controller, NaN/Infinity rejected at the controller layer, cross-Study analysis 404.
- `PostgresStudyRepositoryAiProductAnalysisReviewTest` (5, Testcontainers, real Postgres) — upsert inserts then updates in place (never a second row), disc-level review scoped independently from run review, measurement corrections are genuinely append-only (both rows survive), `ON DELETE CASCADE` from `domain_ai_analysis_runs` removes reviews/corrections, an invalid `review_status` is rejected by the `CHECK` constraint.

## 12. Limits / what stage 78 deliberately does not do

- No `professionalAssignedLevel`/manual level assignment for a `null` 67B2 level — flagged as scope-expanding per the stage brief; a future stage can add it as an explicitly separate field, never as a 67B2 output.
- No optimistic locking/version column — the upsert-by-unique-constraint pattern is atomic per-write at the Postgres level, which was judged sufficient without adding version-column machinery the stage brief explicitly discouraged.
- No change to the pre-existing multiplanar-run review endpoints (`/api/ai/runs/{id}/review`) — left exactly as they were, including their laxer `reviewer` trust model.
- Comment sanitization is a blunt `<`/`>` rejection plus a length cap, not a full HTML sanitizer library — judged proportionate to a plain-text comment field with no legitimate use for markup.

## 13. Next stage (79)

- A `professionalAssignedLevel` field for disc levels 67B2 couldn't localize, explicitly separate from any AI output field.
- Surfacing review/correction history (not just the current state) if a full audit trail becomes a product requirement beyond `domain_audit_events`.
- Longitudinal comparison across a Study's multiple reviewed runs.
