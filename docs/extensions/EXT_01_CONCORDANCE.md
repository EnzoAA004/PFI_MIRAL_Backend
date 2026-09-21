# EXT-01 — Multimodel Evidence Concordance

Not clinical validation, never a diagnosis. AI touched read-only (audit only) — no code, weights,
thresholds, or preprocessing changed. No merge to `main`.

Branches (independent Track B, branched directly from the last pushed EXT-04B5 commit — this
stage does not depend on EXT-05/EXT-02's work):

- Backend: `extensions/ext-01-concordance-backend`, worktree `PFI_MVPTest_Enzo_Backend_ext01`,
  base `b097078`.
- Frontend: `extensions/ext-01-concordance-frontend`, worktree
  `PFI_MVPTest_Enzo_Frontend_ext01`, base `83b9209`.

## Overlap matrix (audited before any rule was written)

| Evidence source | Phenomenon | Level | Side | Finding shape | Comparable? | Reason |
|---|---|---|---|---|---|---|
| Disc-level batch classifier (`DegenerativeFindingsV1Dto`, findingType=`subarticular_stenosis`\|`central_canal_stenosis`\|`neural_foraminal_narrowing`) | Stenosis type | Yes | Yes | Ordinal severity (`normal_mild`/`moderate`/`severe`) | **Yes**, against the interactive classifier below, same phenomenon/level/side | Same wire contract, same ordinal scale |
| Interactive subarticular-ROI classifier (`SubarticularPredictionResponseDto` → same `DegenerativeFindingsV1Dto`) | Stenosis type | Yes | Yes | Ordinal severity | **Yes**, against the batch classifier above | Genuinely independent invocation (on-demand, professional-placed coordinate) of the *same* contract |
| P10.7 disc-degeneration grading (`pfirrmann_grade`/`modic_change`/`disc_herniation`/etc., `DiscDegenerativeFindingsPersistenceService`) | Disc degeneration grade | Yes | N/A | Ordinal/categorical, different vocabulary | **No** | Different phenomenon entirely from stenosis type — comparing a Pfirrmann grade to a stenosis severity would invent a relationship that doesn't exist |
| `AiEvidenceRegion` review status (accepted/observed/rejected) | "Is this AI-drawn region well-placed" | Yes | N/A | Meta-judgment about geometry, not a finding | **No** | Answers a different question than "what is the finding" — modeled as `reviewStatus` on an `EvidenceItem`, never as a second competing phenomenon |
| `AiMeasurementResult`/correction (e.g. canal area mm²) | Numeric measurement | Yes | Sometimes | Continuous number | **Not in v1** | No agreed tolerance/rule exists yet to turn "120mm² vs 118mm²" into a concordance verdict — see Limitations, not invented |
| `MASK_REFERENCE` evidence region | Segmentation raster | Yes | N/A | Raster, no categorical/numeric value | **No** | Nothing to compare against a categorical finding; excluded structurally (an `EvidenceItem` always requires `finding` or `value`) |

**The one real, audited overlap this stage builds on:** the disc-level batch classifier and the
interactive subarticular-ROI classifier both emit the exact same `DegenerativeFindingsV1Dto`
contract, and a professional can trigger the second for the same (level, side) the first already
covered — two genuinely independent computations of the same phenomenon, never merged or invented.

## Contract: `EvidenceItem`

`id, source, modelId, pipelineVersion, caseId, level, side, plane, phenomenon, finding, value,
confidence, experimental, reviewStatus`. `finding` (categorical) and `value` (numeric) are
mutually informative but at least one is required — an item with neither is rejected at
construction. Never persisted; assembled by the caller (today: the Frontend, from findings it
already holds in memory) and sent per-request to a stateless evaluation endpoint.

## `ConcordanceRuleSet v1`

Groups items sharing `(phenomenon, level, side)` — different phenomenon, level, or side are
**never** grouped, by construction of the grouping key (not a runtime check that could be
bypassed). Per group:

- **< 2 items** → `INSUFFICIENT_EVIDENCE` / `SINGLE_SOURCE_ONLY`.
- **Mixed categorical + numeric** in the same group → `NOT_COMPARABLE` /
  `MIXED_CATEGORICAL_AND_NUMERIC_EVIDENCE` — never forced into one comparison.
- **All numeric** → `INSUFFICIENT_EVIDENCE` / `NUMERIC_CONCORDANCE_RULE_NOT_DEFINED_IN_V1` — named
  explicitly as a gap rather than inventing a tolerance band.
- **All categorical, same finding** → `CONCORDANT` / `SAME_FINDING`.
- **All categorical, ordinal scale (`normal_mild < moderate < severe`), one-step spread** →
  `PARTIALLY_CONCORDANT` / `ADJACENT_SEVERITY_STEP` — explicitly "consistencia informacional", per
  instruction never a clinical rule.
- **Ordinal, two-or-more-step spread** → `DISCREPANT` / `SEVERITY_SPREAD_TWO_OR_MORE_STEPS`.
- **Non-ordinal categorical values that differ** (e.g. two different Modic-change types) →
  `DISCREPANT` / `DIFFERENT_NON_ORDINAL_FINDINGS` — compared by exact equality only, no invented
  ordering.

Every result carries `state + reasonCode + comparedEvidenceIds + ruleVersion="v1"` — versioned so
a future `v2` is a new class, never a silent behavior change on this one.

## API

`POST /api/studies/{caseId}/analytics/concordance` — stateless, `requireProfessional`-gated, body
`{evidence: EvidenceItem[]}`, returns `ConcordanceResult[]`. Nothing is persisted; this is a
read-only computation over evidence the caller already has, never a new source of truth.

## Frontend

`EvidenceConcordancePanel.tsx`, a new "Concordancia de evidencia" `<details>` section in
`StudyReviewView.tsx` (same zero-new-navigation pattern as every other "more" section). Assembles
`EvidenceItem`s from the Study's already-in-memory `degenerativeFindings` (batch,
`source="disc_level_batch"`) and `roiFindings` (interactive, `source="subarticular_roi"`) —
exactly the two sources the overlap matrix found comparable — and calls the endpoint on demand
("Calcular concordancia", disabled with zero available findings, never auto-run). Renders each
result as **"Las fuentes de evidencia son [concordantes/discrepantes/...] según la regla v1
([reasonCode traducido])."** — never "confirmado por dos IAs", verified by a dedicated test.

## Tests

Backend: 12 rule-engine tests (`ConcordanceRuleSetV1Test`, all 5 states + grouping-never-merges-
different-phenomenon/level/side + empty-list) + 3 controller tests (auth gate, live evaluation,
empty list) — **15/15 passing**. Full Backend regression: **1004 run, 0 failures, 0 errors, 1
skipped** (JDK 21). Frontend: 7 pure-function tests (state/reason label translation, no
"confirmado por dos IAs", rule version always named) — **7/7 passing**; full
`npm test`/`typecheck`/`lint`/`build` chain green.

## E2E

`docker-compose.ext01-e2e.yml` (namespace `pfi-ext01-*`, ports 8980/8988/55475). Since the
endpoint is stateless, the E2E proves the live round-trip rather than persistence: all 5 states
(`CONCORDANT`, `PARTIALLY_CONCORDANT`, `DISCREPANT`, `INSUFFICIENT_EVIDENCE`, `NOT_COMPARABLE`)
and the `401` unauthenticated guard were exercised against the real Docker Spring Boot app (not
MockMvc) in both cycles. Browser-verified: opened a seeded Study, navigated to "Concordancia de
evidencia", confirmed the panel renders with the correct empty state ("Sin hallazgos clasificados
disponibles todavía para comparar.") and the "Calcular concordancia" button correctly `disabled`
when zero evidence is available — a real fail-safe, not a fabricated pass. Persistence is not
applicable to this stage (nothing is written); this is noted explicitly rather than fabricated.
Two full cycles, both green from a clean `up`, both torn down with `down -v --remove-orphans`
leaving no `pfi-ext01-*` resource behind.

## Limitations

1. No numeric concordance rule exists in v1 — comparing two measurement values needs an agreed
   tolerance band this product does not define yet; named as a gap, not invented.
2. The interactive subarticular-ROI classification itself was not driven end-to-end through the
   mock AI service in this stage's E2E (would need deeper AI-mock setup); the panel's *evidence
   assembly and disabled-state fail-safe* were verified live in the browser instead, and the
   underlying computation was proven exhaustively (all 5 states) against the real Backend.
3. P10.7 disc-degeneration grading and evidence-region review status were audited and explicitly
   excluded from comparison — not silently ignored, but a deliberate "not comparable" finding.
4. `ConcordanceRuleSet v1`'s ordinal scale is hardcoded to the one real 3-value severity enum this
   product has; a future finding type with a different ordinal scale would need its own mapping,
   not a v1 change.

**Status: `EXT_01_CONCORDANCE_READY`** — contract/rule-engine/API/dashboard/2 E2E cycles all
backed by real evidence gathered this session. READY_TO_COMMIT — no commit, no push, no merge
performed.
