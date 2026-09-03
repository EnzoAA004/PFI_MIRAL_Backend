# EXT-07 — Integrated Clinical Workflow Simulation

Not clinical validation. Synthetic patient, synthetic dates, synthetic measurements. No real
patients, no official validation/test datasets, no cloud, no paid resources, no merge to `main`.

Branches (from `extensions/ext-04-visual-evidence-*`, never from `research/post-e50-*`):

- Backend: `extensions/ext-07-integrated-workflow-backend`, worktree
  `PFI_MVPTest_Enzo_Backend_ext07`, base `22d5eb0` (EXT-04B3.5).
- Frontend: `extensions/ext-07-integrated-workflow-frontend`, worktree
  `PFI_MVPTest_Enzo_Frontend_ext07`, base `b999d07` (EXT-04B3).
- AI: **no branch** — audited read-only on `enzo/p10-7-spider-degenerative-multitask` (working
  tree confirmed clean before and after).

## Scenario

Subject `EXT07-SUBJECT-001`, three chronological studies:

| Study | Date | AI original (`majorAxisMm`) | Professional correction | Effective |
|---|---|---|---|---|
| `EXT07-STUDY-A` | 2026-01-10 | 11.9 | — | 11.9 (`ai_original`) |
| `EXT07-STUDY-B` | 2026-02-10 | 12.1 | 12.3 | 12.3 (`professional_correction`) |
| `EXT07-STUDY-C` | 2026-03-10 | 12.9 | — | 12.9 (`ai_original`) |

One month between studies, chronological, same normalised `subjectRef` — matches the existing
history/longitudinal machinery (Stage 84-86, EXT-04A) exactly as it already works; nothing about
that machinery was changed for this scenario.

Additional fixtures for failure modes: `EXT07-STUDY-NO-RUN` (no `AiProductAnalysisRun` at all),
`EXT07-STUDY-FAILED` (one run, `status="failed"`, no measurements), `EXT07-STUDY-CROSS`
(different subject, for the cross-patient fail-closed check).

## Findings / measurements / evidence

- `EXT07-STUDY-A`: one `L4-L5`/disc measurement (11.9mm) and one AI-suggested `BBOX` evidence
  region with a resolvable `baseImage` (EXT-04B3.5).
- `EXT07-STUDY-B`: the `L4-L5`/disc measurement (12.1→12.3), one AI evidence region later
  corrected `BBOX→ELLIPSE` (append-only), one **professional-authored** `POLYGON` region the AI
  never suggested (`origin=professional`, no correction — a genuine new finding, distinguished
  from `ai_suggested`/`ai_validated`/`professional_corrected`), and one AI region on a *different*
  disc level (`L3-L4`) whose disc-level review is `rejected` and never corrected — proving it is
  excluded from `visualEvidence` (`EffectiveAnnotationResolver`, unchanged).
- `EXT07-STUDY-C`: the `L4-L5`/disc measurement (12.9, `ai_original`), one AI evidence region at
  the same level/plane/structure as B's (so the Frontend before/after can pair them), and one
  deliberately **non-comparable** measurement (`canal`, no counterpart on B) to exercise the
  existing "not comparable" longitudinal path.

No automatic degenerative-findings escalation was introduced — P10.7 stays blocked, 67C stays
experimental, exactly as the existing system already enforces; this scenario does not touch that
gating at all.

## Before/After (EXT-07B)

New Frontend section **"Comparación con estudio previo"** (`src/features/reading/
BeforeAfterComparison.tsx` + `beforeAfterDisplay.ts`), inserted into the existing Structured
Report panel, right after the "Evolución" table. For each of the Backend's own
`longitudinalHistory.comparisons[]` rows (never recomputed on the Frontend — same rule the
"Evolución" table already follows):

- fetches the **previous study's own report** (a second `getStudyReport` call) only to read its
  `visualEvidence` — the numeric values/deltas always come from the *current* study's own
  `longitudinalHistory`, never duplicated;
- matches an evidence item on each side by `level`/`plane`/`structure` (best-effort — `null` when
  no match, never a guessed image);
- renders each side with the existing, unmodified `EvidenceRegionOverlay` (EXT-04B3/B3.5) —
  image, overlay, label, source, review state, experimental badge all reused verbatim;
- shows date, level/plane/structure/metric, previous/current value (with the `(corregido)`
  marker when the value came from a professional correction), absolute delta, and percentage
  delta — vocabulary is `Anterior`/`Actual`/`Delta`/`Variación`, never mejoró/empeoró/progresión
  (verified by a dedicated banned-term test, mirroring 84-N).

## Professional correction (EXT-07C)

Simulated exactly as specified: AI produces 12.1mm, a professional corrects it to 12.3mm. Nothing
new was built for this — `AiMeasurementCorrection` (Stage 78) already guarantees the original
12.1 is never overwritten, the effective value resolves to 12.3, and both are visible in the
report/history/before-after. The same append-only guarantee was exercised for one
`AiEvidenceRegionCorrection` (EXT-04B2): the root region stays `BBOX`, the correction row alone
carries `ELLIPSE`, and the report always shows the corrected geometry as the effective one — this
is the one already-proven behavior this iteration relies on, not new code.

## Feedback capture (EXT-07D) — capture only, never training

New: `ProfessionalFeedbackCandidate` (domain) + `domain_professional_feedback_candidates`
(migration `V20260904_020`) + `ProfessionalFeedbackCaptureService` +
`ProfessionalFeedbackController`. Deliberately **not** a new `AiEvidenceRegion` controller — this
iteration was explicitly scoped to avoid that; region creation/correction for the E2E fixture
remains raw SQL, documented as test setup only (see "E2E" below).

- `POST /api/studies/{caseId}/feedback/capture` scans the Study's already-persisted
  (append-only) measurement/evidence-region corrections and professional-authored regions, and
  captures one row per correction/finding it has not already captured — idempotent
  (`UNIQUE(source, correctionId)` at the DB layer, checked again in the service before insert).
- `GET /api/studies/{caseId}/feedback-candidates` lists them. `reviewedBy` is stored (verifiable
  at the database layer for audit) but **never serialized** in either endpoint — same rule
  EXT-04A already applies to reviewer identity in the structured report.
- `PATCH /api/feedback-candidates/{id}` is the *only* way a candidate's status ever changes —
  always an explicit, human-issued call. Allowed values: `captured` (default) →
  `eligible_for_curation` | `excluded` → `curated`. There is **no `ready_for_training` value at
  all** — not "restricted", not "gated behind a role" — it simply does not exist in this
  product's vocabulary (`ProfessionalFeedbackCandidate.ALLOWED_STATUSES`), and any attempt to set
  it (or any other unrecognised string) is rejected with `400`.
- Nothing here trains anything, touches a checkpoint, or runs automatically — capture only
  happens when this endpoint is explicitly called (by a professional, or by the E2E harness
  simulating one).

## Seed vs. tuning vs. training-with-feedback — audited, not assumed

Per the explicit instruction not to assume a random seed improves the model, and to distinguish
the three categories:

**A. Random seed (reproducibility only).** `ai_service/pfi_ai_service/subarticular_frozen_classifier.py`'s
`RuntimeTrainConfig.seed = 2026` is metadata carried over from *how the checkpoint was trained* —
it is never re-applied at inference (`model.eval()`, `requires_grad_(False)`, no
`torch.manual_seed` call and no augmentation anywhere in the real inference path). It documents
training-time reproducibility; it has no effect on, and does not improve, current inference.

**B. Tuning without retraining (audited, not modified).** A real calibration module exists —
`src/lumbar_mri/axial_v3/calibration.py`, `apply_raw0_threshold` (probability/margin gate, with
`min_probability`/`min_margin` parameters) — but it is **not imported anywhere under
`ai_service/pfi_ai_service`**, i.e. it is not wired into the runtime inference path the Backend
actually calls. What *is* live in production: a couple of hardcoded morphology-ratio gates in
`ai_service/pfi_ai_service/agent.py` (`fg_ratio > 0.25` axial / `> 0.45` sagittal) and the
manifest-driven quality gate in `model_artifacts.py` (`qualityGatePassed`/
`heldOutReuseWarning`, versioned per checkpoint manifest). None of these were touched — this
iteration only read and documented them, per the explicit "sólo auditarlos y documentarlos"
instruction.

**C. Model improvement with feedback (not implemented, by design).** No curation pipeline, no
dataset versioning, no offline retraining, no model-vs-model comparison, no redeployment exists
in this product, and none was added. The only piece of the eventual `feedback → curation →
dataset version → offline retraining → validation → model version → deployment` chain this
iteration builds is the very first box: structured, traceable **capture**.

## Determinism check (EXT-07E) — real inference, N=5, read-only

Ran the actual production checkpoint, `models/final/sagittal_spider_multiclass_final_best.pt`
(`modelKey=sagittal_spider`, `qualityGatePassed=true`, `trainingStatus=validated_baseline`),
through the real `pfi_ai_service.pipeline.run_pipeline(..., inferenceMode="real_baseline")` path
5 times, with:

- the same fixed synthetic input array (`np.random.default_rng(20260904).normal(size=(256,256,9))`,
  saved once to `.npy`, reused byte-for-byte across all 5 runs — never a real patient image,
  never an official validation/test case);
- the same checkpoint, same device (`cpu`), same eval-mode model, no code change;
- outputs written only to a scratch directory outside the repository — the checkpoint itself was
  only ever read.

Result: **all 5 runs produced a bit-for-bit identical segmentation mask**
(`sha256=4b5c57251f3db11193a255076fa9f87cdd798eeb947864c7263ac25a754a81f1` on every run, same
`256×256` shape). This is empirical evidence, not an assumption from reading the code: the real
inference path is deterministic for this checkpoint under CPU eval mode with this input. No
`torch.backends.cudnn.deterministic`/`use_deterministic_algorithms` call was found in the runtime
path, but none was needed either — there is no source of randomness in the forward pass to
control (no dropout active in eval mode, no test-time augmentation). GPU floating-point
non-associativity across different hardware was not tested (this environment ran CPU-only) and
is called out as a limitation, not claimed.

This was **product-flow-independent**: it exercises the AI service's own inference code in
isolation, never through the Backend/mock-ai/Docker stack used for EXT-07F. The two are
deliberately kept apart, per the instruction to never mix product E2E with model-performance
validation.

## E2E (EXT-07F)

`docker-compose.ext07-e2e.yml` (namespace `pfi-ext07-*`, ports 8480/8488/55425, distinct from
`pfi-ext04b3-*` so both can coexist) + `scripts/playwright-ext07-integrated-workflow-e2e.mjs`,
modeled on the already-proven EXT-04B3.5 harness. Seeds Study A/B/C (plus the three failure-mode
studies) with raw `psql` `INSERT`s against `domain_studies`/`domain_ai_analysis_runs`/
`domain_ai_measurement_results`/`domain_ai_measurement_corrections`/`domain_ai_evidence_regions`/
`domain_ai_evidence_region_corrections`/`domain_ai_disc_level_reviews` — **explicitly test setup
only**: no `AiEvidenceRegion` controller was added (per instruction), and every assertion reads
through real product APIs (`/report`, `/longitudinal-comparison`, `/feedback/capture`,
`/feedback-candidates`, `/api/ai/series/.../slices/...`) and the real rendered browser app.

Two full cycles (`reset -v → up --build → seed → API assertions → browser assertions → teardown`)
both passed, plus a dedicated persistence check inside Cycle 1: `docker compose restart backend`
(no `-v`, database untouched) followed by re-querying history/report/feedback-candidates via API
and re-loading the browser page — all data, including the explicit `eligible_for_curation` status
change made earlier in the same cycle, survived the restart unchanged. Final teardown (`down -v`)
left no `pfi-ext07-*` container/network/volume behind.

## Frontend manual flow

The scenario is reachable exactly the way a professional would use it: patient list →
`EXT07-SUBJECT-001` → Study A/B/C with their real dates → open Study C → "Más" tab → Structured
Report panel shows history (A/B/C), the new "Comparación con estudio previo" section (Anterior
2026-02-10 / Actual 2026-03-10, images+overlays, 12.3mm → 12.9mm, Variación +0.6mm), the
professional correction on B, visual evidence with provenance/review-state/experimental badges,
and the before/after section — no UI redesign, only the one new section this iteration required.

## Limitations

1. Determinism was verified on CPU only (this environment); GPU-specific floating-point
   non-associativity across different hardware was not tested.
2. The calibration module found in `src/lumbar_mri/axial_v3/calibration.py` is real but unused
   in production — documented, not wired in (out of scope for this iteration).
3. Feedback capture only runs when explicitly called (via the API, or the E2E harness simulating
   a professional action) — it is not yet triggered automatically by the review/correction
   endpoints themselves; that wiring is a decision left for a later iteration, not a gap in this
   one's own scope.
4. No `AiEvidenceRegion` productive controller exists — region creation/correction for real
   professional use (outside this E2E's SQL-seeded fixture) still requires that future work
   (tracked since EXT-04B2).
5. This scenario is synthetic end to end — it demonstrates the product flow, not clinical
   accuracy, and must never be read as validation of the AI's measurements.
