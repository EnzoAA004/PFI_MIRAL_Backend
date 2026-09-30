# EXT-04B5 — Professional Annotation & Correction Flow

Not clinical validation. Synthetic study, synthetic subject, no real patients, no cloud, no paid
resources, no merge to `main`. No retraining, no active/online learning, no checkpoint changes —
AI was touched read-only (audit only) this stage.

Branches (never from `research/post-e50-*`):

- Backend: `extensions/ext-04b5-professional-annotations-backend`, worktree
  `PFI_MVPTest_Enzo_Backend_ext04b5`, base `77d2d8e` (EXT-04B4).
- Frontend: `extensions/ext-04b5-professional-annotations-frontend`, worktree
  `PFI_MVPTest_Enzo_Frontend_ext04b5`, base `e3be68e` (EXT-07) — confirmed by `git merge-base
  --is-ancestor` that EXT-07 Frontend already contains EXT-04B3 Frontend before branching.

## Phase 1 audit — what already existed vs. what this stage adds

- **Missing endpoint**: nothing let a professional create a region (`origin=professional`) or
  correct an AI region's geometry. `EvidenceRegionIngestionService` (EXT-04B2) is the only
  existing write path for AI-origin geometry and is deliberately unwired to any controller —
  unrelated scope, not touched.
- **Already existing and reused, not reinvented**: `EvidenceGeometry.fromMap` (all geometry
  validation), `EffectiveAnnotationResolver` (review/effective resolution), the Stage 78-B/C
  run-level/disc-level review endpoints (`PATCH .../review`, `PATCH .../disc-levels/{id}/review`),
  the `reviewedBy(HttpServletRequest)` JWT-derivation pattern (copied verbatim from
  `AiProductAnalysisController`), `ProfessionalFeedbackCaptureService` (EXT-07D, extended not
  rewritten), `StudyReportService`/`StudyReportPdfRenderer` (unmodified — they only ever read
  what the resolver already produces).
- **UI primitive reused**: `MeasurementLayer.tsx`'s own pointer-capture drag convention
  (`setPointerCapture` on the dragged element, `pointFromEvent`-style client→canonical 0..256
  conversion) and `EvidenceRegionOverlay.tsx`'s read-only rendering (image + SVG shape + caption)
  — `EvidenceRegionEditor.tsx` is a superset of it, never a rewrite.
- **Reasonably editable geometries**: BBOX and ELLIPSE (closed-form move/resize with a stable
  handle model). **Append-only, never touched by an UPDATE**: the original `AiEvidenceRegion` row
  (`domain_ai_evidence_regions`) — every correction is its own row in
  `domain_ai_evidence_region_corrections`.

## API (no redundant endpoints)

`EvidenceRegionAnnotationController`, mounted under the existing
`/api/studies/{caseId}/product-analyses/{analysisId}` path:

- `POST .../evidence-regions` — create a professional region. Request DTO
  (`CreateProfessionalEvidenceRegionRequestDto`) has **no `origin` field at all** — a client
  cannot declare `origin=ai` because there is nothing to smuggle it through; in the deployed
  Spring Boot app an unrecognised JSON property (e.g. a spoofed `"origin":"ai"`) is rejected
  outright with `400 VALIDATION` rather than silently ignored, which is a stricter, more
  fail-closed behavior than assumed at unit-test time (a MockMvc `standaloneSetup` test's default
  `ObjectMapper` is more lenient than the full app's — both still prove the same guarantee: a
  spoofed origin never reaches the domain).
- `POST .../evidence-regions/{regionId}/corrections` — append-only correction.
- **No new review endpoint** — the existing `PATCH .../review` and `PATCH
  .../disc-levels/{id}/review` (Stage 78-B/C) already cover exactly the granularity
  `EffectiveAnnotationResolver` uses to resolve a region's review status.
- **No new "get regions" endpoint was needed either** — `GET .../report` (`visualEvidence[]`)
  already surfaces every region's effective state; the two new endpoints only add writes.

All writes require an authenticated professional (`RoleAuthorizationService.requireProfessional`:
`REVIEWER|DOCTOR|ADMIN`); identity is always derived from `AuthFilter.AUTH_CLAIMS_ATTRIBUTE`
(the JWT claims already resolved by the filter chain), **never** from the request body — proven
both by a unit test (`authoredByComesFromJwtClaimsNeverFromTheRequestBody`) and live in the E2E
stack (a spoofed `origin` in the body is rejected, not honored).

## Domain

- `AiEvidenceRegion` — unchanged (EXT-04B2); `ProfessionalAnnotationService` is simply a new
  caller of its existing constructor, with `origin="professional"` hardcoded server-side.
- `AiEvidenceRegionCorrection` — unchanged (EXT-04B2); append-only, carries both
  `originalGeometryType/Geometry` (snapshotted from the *current effective* geometry at
  correction time — so a second correction snapshots the first correction's result, not always
  the AI's original) and `correctedGeometryType/Geometry`.
- `EffectiveAnnotationResolver` — unchanged. Policy (already existed, now exercised by a new
  write path): a correction, once it exists, is always effective; the newest correction by
  `createdAt` wins over an older one on the same region; a `professional`-origin region with no
  correction is always effective; an `ai`-origin region is effective only if
  accepted/observed (or pending when the consumer allows unreviewed suggestions), and never
  effective if rejected with no correction (a correction can still supersede a rejection).

## Concurrency / history

A region can accumulate `AI original → correction 1 → correction 2 → …` — every row is kept,
ordered by `created_at`, and the resolver always picks the latest by timestamp. Verified in two
ways: (1) a unit test using two `ProfessionalAnnotationService` instances on genuinely different
fixed clocks (a single shared clock in the test made two corrections carry an identical
`Instant.now()`, which is a *test* artifact — never possible for two real HTTP requests — not a
production race); (2) live in Docker, where a browser-driven drag/resize correction was saved
*after* an API-driven correction on the same region, and the report/DB both showed the browser's
geometry as effective while the first correction row remained untouched.

## Feedback capture (extends EXT-07D, not a new pipeline)

`ProfessionalFeedbackCaptureService` gained a third capture loop and a 4th `source` value,
`rejected_ai_suggestion` (migration `V20260905_021`, widening the existing `source` CHECK
constraint on `domain_professional_feedback_candidates`): an AI-origin region with no correction
whose resolved review status is `"rejected"` is captured as its own feedback source, using
`EffectiveAnnotationResolver.effectiveAnnotations(run, true)` to resolve that status rather than
re-deriving it. The two sources this stage's flow actually produces —
`"evidence_region"` (a correction) and `"professional_new_finding"` (a professional-drawn
region) — already existed from EXT-07D and needed no change. All capture remains **idempotent**
(`UNIQUE(source, correction_id)`), **capture-only** (status never auto-advances past
`"captured"`), and requires an explicit `POST /api/studies/{caseId}/feedback/capture` call — never
triggered automatically by a correction/creation/review action itself (same limitation EXT-07D
already documented, unchanged).

## Frontend — edit mode

`EvidenceRegionEditor.tsx` (`src/features/reading/`) supersedes `EvidenceRegionOverlay.tsx` at the
Structured Report's visual-evidence render site (`StudyReviewView.tsx`); `EvidenceRegionOverlay`
itself is untouched and still used verbatim by `BeforeAfterComparison.tsx` for the previous
study's own evidence, which stays read-only by design (never overwritten by a correction made on
the current study).

- **Read-only by default.** No drag handler is attached until "Editar anotación" is clicked.
- **Editable geometries**: BBOX (move whole box + 4 corner-resize handles) and ELLIPSE (move
  center + independent rx/ry handles). **POLYGON stays read-only** this iteration — its "Editar
  anotación" button is replaced with a disabled note explaining why (a clean minimum-3-point
  vertex editor was judged out of scope for this stage's closing budget; see Limitations).
  **MASK_REFERENCE is never editable** — it is a raster asset, not a vector shape; its own disabled
  note says so explicitly.
- **Safety**: the draft shape is a solid red stroke (`rgb(220 38 38)`, same "región señalada"
  color already used by the PDF overlay in EXT-04B4) with a `fill-opacity` wash, but the caption
  always also carries the text "Región señalada — no indica gravedad clínica." — color is never
  the only cue, matching every prior stage's safety convention.
- **Confirmation flow**: edit → drag (all pointer moves stay local React state, no network call)
  → "Guardar corrección" (posts once) or "Cancelar" (discards the draft, zero requests). There is
  no autosave during drag.
- **Undo**: "Cancelar" before saving reverts to the previous effective geometry, no local history
  needed; after saving, the Backend's append-only design is the only undo mechanism (a further
  correction), matching the spec's explicit "no complex local history".
- **Refetch**: both `EvidenceRegionEditor`'s `onSaved` and `NewEvidenceRegionCreator`'s `onCreated`
  call the same `setStudyReportRefresh` state bump `StudyReviewView.tsx` already used for its
  "Actualizar" button — one refetch path, never duplicated.
- **"Nueva región"**: `NewEvidenceRegionCreator.tsx`, its own card below the evidence list. Select
  BBOX/ELLIPSE → drag inside a small preview (rendered over the current plane/slice's own
  authenticated slice image, reusing `evidenceBaseImageSliceUrl` — no new asset mechanism) →
  optional label/note → "Guardar región". Saved with `origin=professional` (server-derived).

All geometry math (`clampCanonical`, `moveBbox`, `resizeBboxHandle`, `moveEllipseCenter`,
`resizeEllipseRadius`, `pointFromClient`, `geometryToRequestMap`) lives in
`evidenceRegionEditing.ts`, framework-free and unit-tested directly — the interactive components
are thin wrappers that never duplicate this logic.

## Report / PDF integration

Neither `StudyReportService` nor `StudyReportPdfRenderer` needed a single line of change: both
already only ever read `EffectiveAnnotationResolver`'s output. Proven twice:

1. **Unit level** (`ProfessionalAnnotationEndToEndReportTest`, Backend): seeds an AI BBOX in a
   shared `InMemoryStudyRepository`, corrects it through `ProfessionalAnnotationService`, and
   asserts the *same repository's* `StudyReportService.report(...)` now shows
   `source=professional_corrected` with the new geometry, and `reportPdf(...)` returns a real,
   larger PDF.
2. **Full stack, live** (Docker E2E): `GET .../report` before/after a real HTTP correction shows
   `BBOX/ai_suggested` → `ELLIPSE/professional_corrected`; `GET .../report.pdf` returns
   `200` with a real PDF (`%PDF-1.6`, 6271–6918 bytes across both cycles) generated *after* the
   correction, with the AI's original `BBOX` geometry still intact and unchanged in
   `domain_ai_evidence_regions` at the same time.

## Before/after

Untouched — `BeforeAfterComparison.tsx` already renders each side's evidence through the
resolver's own effective annotation (whichever study it belongs to), so a correction on the
*current* study was never able to retroactively alter a *previous* study's own persisted
evidence — there is no code path that writes across studies at all.

## Persistence

Verified in both E2E cycles: `docker compose restart backend` (no `-v`, database untouched)
followed by re-querying `/report` and `/feedback-candidates` showed every corrected geometry,
professional-drawn region, and captured feedback candidate unchanged; a full browser reload after
the restart kept the session (IndexedDB-backed) and the worklist/report state.

## Security / failure modes (all fail-closed, verified live)

| Case | Result |
|---|---|
| Unauthenticated write (create or correct) | `401`, no auth filter bypass |
| Client-declared `origin=ai` in the create body | `400 VALIDATION` (rejected outright) |
| `authoredBy`/`reviewedBy` in the request body | Silently has no effect — identity is always the JWT's, verified by a dedicated spoofing test |
| Correction referencing a region of a different study/run | `404` via `requireRunOfStudy`/`requireRegionOfRun` ownership checks |
| Invalid geometry (missing fields, wrong shape) | `400`, reuses `EvidenceGeometry.fromMap`'s existing validation — no new validation code |
| Out-of-range coordinates (outside 0..256) | `400`, same canonical-range check every geometry variant already enforces |
| `MASK_REFERENCE` on create or correct | `400 MASK_REFERENCE_NOT_EDITABLE` — explicit guard, not merely UI-side |
| Path traversal / external URL in a mask asset name | N/A for this stage's writes — `MASK_REFERENCE` is rejected before any asset path is touched |
| Reviewer identity leakage | `EvidenceRegionResponseDto`/`EvidenceRegionCorrectionResponseDto` omit `authoredBy`/`reviewedBy` entirely — verified by reflection-based and MockMvc `jsonPath(...).doesNotExist()` tests |

## E2E

`docker-compose.ext04b5-e2e.yml` (namespace `pfi-ext04b5-*`, ports 8680/8688/55445, network
`pfi-ext04b5-e2e`, volume `pfi-ext04b5-postgres-data` — distinct from every other stage's
namespace so they can coexist). Seed: one Study (`EXT04B5-STUDY-01`, subject
`EXT04B5-SUBJECT`), one `domain_input_resources` row and one `domain_study_runs` row (needed for
the Reading Room's tab bar to render at all — a known pattern from EXT-07's own E2E work), one
`AiProductAnalysisRun`, and one AI-origin `BBOX` evidence region — **raw SQL, explicitly test
setup only**, matching EXT-07's own precedent: there is still no productive ingestion path for the
*initial* AI-origin region (`EvidenceRegionIngestionService` remains deliberately unwired,
unrelated scope). Every subsequent action (correct, create, review-adjacent feedback capture, PDF)
goes through the real product APIs and the real rendered browser app — this is exactly the gap
EXT-04B5 fills.

Both cycles (`up --build` → seed → API assertions → browser assertions incl. real pointer
drag/resize + a real "Nueva región" canvas drag → `report.pdf` → `restart backend` (no `-v`) →
re-assert persistence → reload browser → `down -v --remove-orphans`) passed from a clean setup.
Cleanup was scoped exclusively to `pfi-ext04b5-*` (containers, network, volume) — confirmed empty
after each teardown.

Browser-verified in Cycle 1: opened the seeded Study, saw the AI `BBOX` read-only, clicked
"Editar anotación", dragged the ellipse body (real `left_click_drag`, not a scripted geometry
assignment) to move its center, dragged its `rx` handle to resize, clicked "Guardar corrección" —
the resulting geometry (`centerX≈161.05, centerY≈160.35, radiusX≈62.84, radiusY=12`) appeared
verbatim as the newest row in `domain_ai_evidence_region_corrections`, confirming the full
pointer-event → React state → HTTP → Postgres path with no shortcuts. Then used "Nueva región" to
drag-place a fresh BBOX inside its own preview card and saved it — it appeared in `/report` as
`origin=professional`/`professional_drawn` with the exact dragged coordinates. Clicking the
existing "PDF" export button issued a real `GET .../report.pdf` (200).

## Limitations

1. **POLYGON is read-only this iteration.** A clean vertex editor (add/remove/move a point,
   enforce ≥3 points, without a free-form creation tool that could produce self-intersecting or
   degenerate shapes) was judged too large to fit this stage's closing budget alongside BBOX,
   ELLIPSE, creation, feedback, and the E2E harness — tracked as future work, not silently
   dropped.
2. **`MASK_REFERENCE` is never editable**, by design — it is a raster asset, not a vector shape;
   editing it would mean re-painting a mask, a materially different (and out-of-scope) feature.
3. **The initial AI-origin evidence region still has no productive ingestion controller** —
   unchanged from EXT-04B2/EXT-07; this stage only adds the *professional-facing* create/correct
   surface on top of whatever AI-origin regions already exist by whatever mechanism populates
   them.
4. **Feedback capture is still explicit, not automatic** — a correction/creation does not itself
   trigger `POST .../feedback/capture`; unchanged limitation from EXT-07D.
5. **No new review endpoint/state was added** — a region's review status is only ever as granular
   as the disc-level/run-level review it inherits from; there is no per-evidence-region review
   action distinct from that.
6. **This scenario is synthetic end to end** — it demonstrates the product flow, not clinical
   accuracy, and must never be read as validation of the AI's measurements.
