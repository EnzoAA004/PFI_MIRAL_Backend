# EXT-05 — Professional Feedback Curation

Not clinical validation. Synthetic study, synthetic subject, no real patients, no cloud, no paid
resources, no merge to `main`. No retraining, no active/online learning, no checkpoint changes,
no automatic dataset-for-training generation — this stage only turns the capture-only feedback
EXT-07D/EXT-04B5 already produce into an explicit, human-curated classification.

Branches (never from `research/post-e50-*`):

- Backend: `extensions/ext-05-feedback-curation-backend`, worktree
  `PFI_MVPTest_Enzo_Backend_ext05`, base `b097078` (EXT-04B5, the most complete Backend line).
- Frontend: `extensions/ext-05-feedback-curation-frontend`, worktree
  `PFI_MVPTest_Enzo_Frontend_ext05`, base `83b9209` (EXT-04B5 Frontend).

## What already existed vs. what this stage adds

Audited before writing code: `ProfessionalFeedbackCandidate` already modeled the 4 states
(`captured|eligible_for_curation|excluded|curated`, deliberately no `ready_for_training`),
`ProfessionalFeedbackCaptureService`/`ProfessionalFeedbackController` already had capture
(`POST .../feedback/capture`), list (`GET .../feedback-candidates`), and a single-field status
update (`PATCH /api/feedback-candidates/{id}`), and `findProfessionalFeedbackCandidateById` was
already implemented at the repository layer (just not wired to a controller endpoint). This stage
did **not** rebuild any of that — it extended it:

- **New**: `curatorId`/`curatedAt`/`exclusionReason` fields on the candidate (internal-only for
  `curatorId`, same rule as `reviewedBy`); a separate append-only
  `domain_professional_feedback_status_changes` history table (migration `V20260906_022`) so a
  curation decision is never a bare overwrite; `GET /api/feedback-candidates/{id}` (detail — the
  repository method already existed, just needed a route); `GET
  /api/feedback-candidates/{id}/history`; query-param filters (`state`, `type`, `level`, `plane`,
  `model`, `from`, `to`) on both the list and export endpoints; `GET
  /api/studies/{caseId}/feedback/export` producing a `FeedbackDatasetExport` manifest.
- **Reused unchanged**: the capture logic itself (all 4 sources — `measurement`,
  `evidence_region`, `professional_new_finding`, `rejected_ai_suggestion` — were already captured
  by EXT-07D/EXT-04B5); the `RoleAuthorizationService.requireProfessional` gate; the JWT-only
  identity derivation pattern (copied from `EvidenceRegionAnnotationController`).
- **Audited, not implemented**: a `classification` correction source. No classification-correction
  domain concept with an append-only correction record exists elsewhere in this codebase (the
  degenerative-findings/subarticular-ROI classifiers are read-only, unreviewed outputs — there is
  no `AiClassificationCorrection` analogous to `AiMeasurementCorrection`). Documented as a gap,
  not invented.

## Backend

### States and fields

`ProfessionalFeedbackCandidate` now carries, alongside its EXT-07D fields: `curatorId` (internal
only — never serialized, verified by a controller test), `curatedAt`, `exclusionReason` (only ever
set when `status="excluded"`). Every status transition is recorded as its own row in
`domain_professional_feedback_status_changes` (`fromStatus`, `toStatus`, `curatorId` — internal
only —, `reason`, `note`, `changedAt`) — the candidate row always reflects the *current* state,
the history table is the durable append-only audit.

### Endpoints

All under the existing `ProfessionalFeedbackController`:

- `POST /api/studies/{caseId}/feedback/capture` — unchanged (EXT-07D).
- `GET /api/studies/{caseId}/feedback-candidates?state=&type=&level=&plane=&model=&from=&to=` — all
  filters optional, applied in-memory over the study's own (synthetic-scale) candidate set.
- `GET /api/feedback-candidates/{id}` — single-candidate detail.
- `GET /api/feedback-candidates/{id}/history` — the full append-only curation history (never
  includes `curatorId`).
- `PATCH /api/feedback-candidates/{id}` — body `{status, reason, note}`; `reason` is only stored
  as `exclusionReason` when `status="excluded"`. `ready_for_training` (or any value outside the 4
  allowed states) is rejected with `400` — verified live (see E2E).
- `GET /api/studies/{caseId}/feedback/export?...same filters...` — `FeedbackDatasetExport`:
  `schemaVersion="pfi.feedback-dataset-export.v1"`, `exportedAt`, `candidateCount`,
  `sourceModelVersions` (distinct, sorted `modelId`s among the exported candidates), `records`
  (the same public `ProfessionalFeedbackCandidateDto` every other endpoint returns — never the
  domain record, which would leak `curatorId`/`reviewedBy`), `manifestChecksum` (SHA-256 over the
  serialized `records`). CSV export was not implemented — explicitly optional per spec; JSON alone
  satisfies "dataset manifest + candidate records with a version id/hash". No training dataset or
  training run is ever produced automatically — this is a read-only snapshot of whatever the
  filters select.

## Frontend

New `FeedbackCurationPanel.tsx`, inserted as a new `<details>` disclosure ("Feedback / Curación")
in the Structured Report tab of `StudyReviewView.tsx` — the same zero-new-navigation-state pattern
EXT-04B5's evidence editor and every other "more" section already use. Shows a table: type
(neutral labels — "Medición corregida", never "error del modelo"), level/plane, AI original vs.
professional value/geometry, numeric delta when both sides are numeric, model id, curation state
(with the exclusion reason inline when excluded). Actions: "Marcar elegible" / "Excluir" (with a
required-in-spirit, not enforced, reason input) / "Marcar curado" — each a single `PATCH` call,
gated server-side by `requireProfessional` (not re-implemented client-side). "Capturar feedback
nuevo" and "Exportar dataset (JSON)" (triggers a browser download of the export manifest) round
out the panel. No UI path retrains anything — there is no button that could.

## Tests

Backend: 8 new/updated tests in `ProfessionalFeedbackControllerTest` (detail, 404, curator-derived-
from-JWT-never-body, exclusion-reason-carried, `ready_for_training` still rejected) + 4 new in
`ProfessionalFeedbackCaptureServiceTest` (append-only audit history, exclusion-reason-only-on-
exclude, export manifest/checksum/no-leak) — **20/20 passing**. Full Backend regression: **995
run, 0 failures, 0 errors, 1 skipped** (JDK 21). Frontend: 10 new pure-function tests
(`ext05-feedback-curation-tests.mjs` — delta calc, label display, no `ready_for_training`, no
training-language leakage) — **10/10 passing**; full `npm test`/`typecheck`/`lint`/`build` chain
green.

## E2E

`docker-compose.ext05-e2e.yml` (namespace `pfi-ext05-*`, ports 8780/8788/55455). Seed (raw SQL,
test setup only, same convention as EXT-04B5/EXT-07): one Study, one `AiProductAnalysisRun`, one
disc-level result, one measurement, two AI evidence regions. Every subsequent action goes through
real product APIs: correct the measurement (Stage 78-D endpoint), correct one evidence region and
create a new professional one (EXT-04B5 endpoints), reject the other region's disc-level review
(Stage 78-B/C endpoint) — producing all 4 feedback sources in one real capture call. Curated 2
eligible / 1 excluded (with reason) / 1 curated; verified `ready_for_training` still `400` and
unauthenticated curation still `401`; verified filters, detail, history (no `curatorId` leak), and
export (schema/count/checksum). Verified live in the browser: the Feedback / Curación panel
rendered all 4 candidates with their real curated states, and a real click on "Marcar curado"
persisted to the Backend (confirmed via API). Persistence verified via `docker compose restart
backend` (no `-v`) after Cycle 1's curation actions. Two full cycles, both green from a clean
`up`/reseed, both torn down with `down -v --remove-orphans` leaving no `pfi-ext05-*` resource
behind.

## Limitations

1. No `classification` correction feedback source exists — audited, not invented (see above).
2. CSV export not implemented (explicitly optional in spec); JSON manifest covers the required
   dataset-manifest-plus-records-plus-checksum shape.
3. Filtering is in-memory over a study's own candidate list, not a SQL `WHERE` — fine at this
   product's synthetic/demo scale, would need revisiting for a large multi-study export.
4. Curation is still per-candidate (no bulk "mark all eligible" action) — deliberately kept simple
   for this stage; each decision is its own explicit, auditable call.
5. This scenario is synthetic end to end — it demonstrates the curation workflow, not a real
   curation policy or dataset quality judgment.

**Status: `EXT_05_FEEDBACK_CURATION_READY`** — create/capture/curate/export/filter/history/
browser/persistence/2 E2E cycles all backed by real evidence gathered this session. `git status`
clean of anything unexpected on both worktrees (see master report). READY_TO_COMMIT — no commit,
no push, no merge performed; awaiting separate authorization.
