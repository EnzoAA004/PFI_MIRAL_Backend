# PFI RM Lumbar — Extension Freeze Manifest

Generated at the close of the 2026-09 extensions push. No merge to `main` performed by this
manifest or by any work it describes. All branches below are pushed/unpushed exactly as stated;
nothing here authorizes a merge.

## Baseline (pushed, committed)

| Component | SHA | Branch |
|---|---|---|
| Backend | `b097078fbdbd908fea5ea467c9015b9dcb2d1a53` | `extensions/ext-04b5-professional-annotations-backend` |
| Frontend | `83b9209db8a64d96b48f007917c52d5eff4999b0` | `extensions/ext-04b5-professional-annotations-frontend` |
| AI | `f5b938036af4a7858e712465a7b27018ca76dc02` | `enzo/p10-7-spider-degenerative-multitask` (read-only base) |

## Extensions — capability status

| Extension | Backend branch | Frontend branch | AI branch | Status |
|---|---|---|---|---|
| EXT-04B5 Professional Annotation Flow | `extensions/ext-04b5-professional-annotations-backend` | `extensions/ext-04b5-professional-annotations-frontend` | — | **READY** (committed + pushed) |
| EXT-05 Feedback Curation | `extensions/ext-05-feedback-curation-backend` | `extensions/ext-05-feedback-curation-frontend` | — | **READY** (uncommitted, `READY_TO_COMMIT`) |
| EXT-02 Review Analytics | `extensions/ext-02-review-analytics-backend` | `extensions/ext-02-review-analytics-frontend` | — | **READY** (uncommitted, `READY_TO_COMMIT`) |
| EXT-01 Multimodel Evidence Concordance | `extensions/ext-01-concordance-backend` | `extensions/ext-01-concordance-frontend` | — | **READY** (uncommitted, `READY_TO_COMMIT`) |
| EXT-03 Classifier Explainability (Grad-CAM) | `extensions/ext-03-explainability-backend` | `extensions/ext-03-explainability-frontend` | `extensions/ext-03-explainability-ai` | **READY**, with one named E2E scope limitation (uncommitted, `READY_TO_COMMIT`) |
| EXT-06 Real LLM Validation | `extensions/ext-06-real-llm-validation` | — | — | **BLOCKED** — `EXT_06_REAL_LLM_BLOCKED_BY_LOCAL_HARDWARE`, infrastructure audited and confirmed ready, real inference not run (uncommitted) |
| EXT-08 Final Integrated Demo | — | — | — | **NOT IMPLEMENTED** — see reason below |
| EXT-09 Professional Validation Prep | `extensions/ext-09-professional-validation-prep` | — | — | **READY** (instrument prepared, no sessions run; uncommitted) |
| EXT-10 Extension Freeze | `extensions/ext-10-extension-freeze` | — | — | **READY** (this document; uncommitted) |

### Why EXT-08 is `NOT IMPLEMENTED`

EXT-01/02/03/05 were each built in their own independent worktree, branched directly from the
EXT-04B5 baseline (Track B/C independence) or with EXT-05's changes copied — still
uncommitted — onto EXT-02's tree (Track A dependency). Building the single combined
`pfi-extension-demo-*` stack this stage calls for requires first reconciling all four sets of
uncommitted changes into one buildable tree (either by committing each extension first — which
the standing instruction explicitly forbids doing automatically — or by manually merging four
divergent working trees file-by-file), and only then standing up a fresh, heavier multi-service
Docker stack exercising all sixteen flow steps end-to-end with two full Playwright cycles. Given
this session's remaining time budget and this machine's memory pressure during the individual
extensions' own Docker E2E runs (free RAM measured as low as ~0.8 GB at points in this session),
attempting that combined build now was judged the conservative call to skip rather than risk a
half-finished, unverified demo stack. It is scoped and ready to build in a follow-up session once
the professional-authorized extensions above have been committed (unblocking a clean multi-repo
base to build the demo from).

## Per-capability classification

| Capability | Classification |
|---|---|
| Professional annotation create/correct (EXT-04B5) | READY |
| Professional feedback curation (EXT-05) | READY |
| Review analytics telemetry (EXT-02) | READY |
| Evidence concordance (EXT-01) | READY |
| Grad-CAM explainability (EXT-03) | READY (experimental, by design — the heatmap itself is explicitly `experimental=true` in its own contract) |
| Real local LLM chat | BLOCKED (hardware) — existing self-hosted-provider architecture is READY; a real model instance is not running |
| Mock LLM chat fallback | READY (already covered by the full Backend regression suite) |
| Integrated final demo | NOT_IMPLEMENTED |
| Professional validation instrument | READY (prep only, no sessions executed) |
| P10.7 disc-degeneration automatic escalation | BLOCKED (unchanged — explicitly not touched, per standing instruction never to enable it automatically) |
| 67C subarticular classifier "validated" status | EXPERIMENTAL (unchanged — explicitly never elevated, per standing instruction) |

## Tests / E2E summary (this push)

| Extension | Backend tests | Backend full suite | Frontend tests | E2E cycles |
|---|---|---|---|---|
| EXT-04B5 | 27 new | 989/0/0/1 | 27 new | 2/2 green |
| EXT-05 | 20 new | 995/0/0/1 | 10 new | 2/2 green |
| EXT-02 | 10 new | 1005/0/0/1 | 8 new | 2/2 green, hand-verified numbers |
| EXT-01 | 15 new | 1004/0/0/1 | 7 new | 2/2 green (stateless endpoint — live round-trip, not persistence) |
| EXT-03 | 6 new (+8 real-checkpoint AI tests) | 995/0/0/1 | 6 new | No full 3-tier Docker E2E — real non-mocked AI inference proven directly instead (see EXT-03 doc) |
| EXT-06 | — (audit only) | n/a | n/a | Not applicable — no inference run |

## Known limitations / blocked items (carried forward, unchanged this stage)

- P10.7 disc-degeneration automatic inference remains blocked on preprocessing parity — untouched.
- 67C subarticular classifier remains experimental — never elevated to validated.
- No classification-correction feedback source exists (audited in both EXT-05 and EXT-02, same
  finding, not invented).
- No numeric concordance rule exists in EXT-01 v1 (no agreed tolerance band).
- EXT-03's heatmap explains only the visual backbone branch, not the side/level embeddings.
- EXT-06's real inference was not run; architecture is ready, hardware currently is not.

## Git state at freeze time

Every extension worktree listed above (except EXT-04B5, already pushed) shows `git status`
containing **only** the files its own stage's report already itemized — verified via `git diff
--check` (clean) and `git status --short` immediately before writing this manifest. No worktree
touched `main`, `research/post-e50-*`, or any AI checkpoint/weights file. No commit, no push, no
merge was performed for EXT-05/EXT-02/EXT-01/EXT-03/EXT-06/EXT-09/EXT-10 — all remain
`READY_TO_COMMIT`, awaiting separate explicit authorization.

## Defense checklist

1. Can explain why each `NOT_IMPLEMENTED`/`BLOCKED` item is exactly that, with the concrete
   evidence gathered this session (hardware readings, dependency audits) — not a guess.
2. Every `READY` item has a real, reproducible test suite and (where applicable) 2 green Docker
   E2E cycles backing it, documented in that extension's own `docs/extensions/EXT_*.md`.
3. Nothing here claims clinical validation, diagnosis, or safety beyond what each extension's own
   safety language already states.
4. No professional-validation result is fabricated — EXT-09 is a prepared instrument only.
