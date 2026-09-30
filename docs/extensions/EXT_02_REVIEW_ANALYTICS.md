# EXT-02 — Review Feedback Analytics

Not clinical validation. Descriptive telemetry only — never "clinical accuracy" or "model
safety". No merge to `main`, no paid resources, synthetic data for E2E.

Branches (built on EXT-05's uncommitted working tree, per instruction — see "Branch basis" below):

- Backend: `extensions/ext-02-review-analytics-backend`, worktree
  `PFI_MVPTest_Enzo_Backend_ext02`.
- Frontend: `extensions/ext-02-review-analytics-frontend`, worktree
  `PFI_MVPTest_Enzo_Frontend_ext02`.

## Branch basis (important, read before Git close)

Per instruction, EXT-02 "parte desde EXT-05, porque necesita su modelo de feedback". EXT-05 was
still uncommitted (`READY_TO_COMMIT`, not committed anywhere) when this stage started, and the
standing instruction is **no automatic commits**. Resolution: both EXT-02 worktrees were created
with `git worktree add` from the last **pushed** commit (EXT-04B5, `b097078`/`83b9209`), then
EXT-05's uncommitted working-tree changes were copied file-by-file on top (same content, still
completely uncommitted, in both the EXT-05 and EXT-02 worktrees independently). No git history was
touched to make this happen — it is pure filesystem copying of already-known, already-tested
files. In practice **EXT-02 does not actually depend on EXT-05's curation additions** — the
metrics below are computed directly from `AiMeasurementCorrection`/`AiEvidenceRegionCorrection`/
review status, all already present since EXT-04B2/Stage 78-D — but the copy was made anyway to
honor the literal instruction and keep both extensions' trees consistent with each other.

## Backend

`ReviewAnalyticsService` (read-only, writes nothing) walks every `Study` → `AiProductAnalysisRun`
→ its measurements/corrections/evidence-regions/region-corrections, using
`EffectiveAnnotationResolver` (unchanged) to resolve each region's review status — never
re-deriving that logic. `GET /api/analytics/review-feedback?caseId=` (caseId optional — omitted
aggregates across every study this Backend has ever persisted).

### Measurement analytics

Each `AiMeasurementResult` row can carry up to 3 independent metrics (`areaMm2`, `majorAxisMm`,
`minorAxisMm`, each its own unit — `mm²` vs `mm`, never mixed). Every populated metric becomes its
own "instance"; grouped by `(level, plane, structure, metric, unit, releaseId)`. Per group:
`automaticCount`, `correctedCount`, `correctionRate`, `meanAbsoluteError` (`|corrected -
automatic|`, averaged), `medianAbsoluteError`, `signedMeanError` (descriptive bias, not a claim of
directionality causation). When a measurement has been corrected more than once, the **latest**
correction by `createdAt` is the one compared — verified by a dedicated test.

### Evidence region analytics

Counts of AI-origin regions by resolved review status (`accepted`/`observed`/`rejected`/`pending`),
`correctionRate`, `professionalDrawnCount` (origin=professional, uncorrected — a genuine new
finding, not "IA falló"). **Geometry correction delta** — computed only for a correction whose
original and corrected geometry are the **same shape** (`BBOX→BBOX` or `ELLIPSE→ELLIPSE`): centroid
displacement (Euclidean distance between centers), area delta, and — **BBOX only** —
intersection-over-union. A shape-changing correction (e.g. `BBOX→ELLIPSE`) or anything touching
`MASK_REFERENCE`/`POLYGON` is skipped outright, never forced into a number that wouldn't mean the
same thing across rows (this is explicit in the code and covered by a dedicated test).

### Classification aggregation — audited, not invented

No `AiClassificationCorrection`-equivalent append-only correction record exists anywhere in this
codebase (the degenerative-findings/subarticular-ROI classifiers are read-only, unreviewed
outputs). `ReviewAnalyticsReport.limitations` names this gap explicitly rather than inventing a
placeholder metric — same discipline EXT-05 already applied to the same question.

## Frontend

New global "Review Analytics" screen (`ReviewAnalyticsView.tsx`), reachable from the profile menu
(`Header.tsx`, next to "Ver / editar perfil") via a new `ViewKey="analytics"` / route `/analytics`
— a genuinely new navigation destination since this is a cross-study dashboard, not something a
single Study's tab bar can host. Cards for the top-line counts/rates, a table grouped by
level/plane/structure/metric (never mixing units in one row), a table of geometry deltas, and the
literal `limitations[]` list from the Backend response rendered as its own section. Vocabulary is
strictly "telemetría interna" / "tasa de corrección" / "error vs. corrección profesional" — no
traffic-light coloring, no "modelo seguro" or "accuracy clínica" anywhere (verified by a dedicated
test asserting the disclaimer only ever *negates* those claims).

## Tests

Backend: 10 new tests in `ReviewAnalyticsServiceTest` — every assertion is a hand-computed exact
value (MAE, median, signed mean, IoU, centroid displacement), not just "some number", including
the latest-correction-wins case, the shape-change-skipped case, the cross-study aggregation case,
and the explicit-limitations case. Full Backend regression: **1005 run, 0 failures, 0 errors, 1
skipped** (JDK 21). Frontend: 8 new pure-function tests (`ext02-review-analytics-tests.mjs` —
label formatting, percent/number formatting, no-fabricated-zero-for-null, disclaimer wording);
full `npm test`/`typecheck`/`lint`/`build` chain green.

## E2E

`docker-compose.ext02-e2e.yml` (namespace `pfi-ext02-*`, ports 8880/8888/55465). Seed (raw SQL,
test setup only): one study, two measurements, two AI evidence regions, one disc-level result.
Real HTTP actions: correct both measurements (12.0→12.5, 10.0→9.0), correct one region's BBOX
(0,0,10,10 → 5,5,10,10), accept the disc-level review (covering both regions, since they share the
disc-level result). Fetched `GET /api/analytics/review-feedback` (both scoped and global) and
confirmed **every number by hand**: MAE = mean(0.5, 1.0) = 0.75, median = 0.75, signed mean =
mean(+0.5, −1.0) = −0.25, centroid displacement = √(5²+5²) = 7.071, area delta = 0 (100→100), IoU =
25/175 = 0.1428... — all matched exactly, both via direct API call and rendered live in the
browser's Review Analytics screen. Verified unauthenticated access `401`s. Persistence verified via
`docker compose restart backend` (no `-v`) in both cycles. Two full cycles, both green from a clean
`up`/reseed, both torn down with `down -v --remove-orphans` leaving no `pfi-ext02-*` resource
behind.

## Limitations

1. No classification-correction aggregation — no stable contract exists to compute it from (see
   above).
2. IoU is only computed for BBOX→BBOX; ELLIPSE→ELLIPSE reports centroid displacement and area
   delta but not IoU (no closed-form-simple ellipse-intersection implemented this stage).
3. "Model/version" grouping uses the analysis run's `releaseId` — there is no separate
   per-measurement model identifier in this product's measurement contract, so this is the best
   available proxy, not a true per-model-checkpoint breakdown.
4. This telemetry describes divergence between automatic and professional values — it is not a
   clinical accuracy measurement and must never be read as one.

**Status: `EXT_02_REVIEW_ANALYTICS_READY`** — metrics/grouping/geometry-delta/dashboard/2 E2E
cycles/persistence all backed by real, hand-verified evidence gathered this session.
READY_TO_COMMIT — no commit, no push, no merge performed.
