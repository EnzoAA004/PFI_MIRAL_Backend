# Stage 89 - Demo Mode / Presentation Fixtures

## Status

Final status: `DEMO_MODE_READY`.

Fixture: `post-e50-demo-fixture-v1`.

Two full `reset -> seed -> browser E2E` cycles passed strict (0 unexpected HTTP failures, 0 console
errors), plus a same-stack re-seed with no reset (idempotency: no duplicate Studies/runs/
corrections). See `89_FULL_DOCKER_E2E.md` (general report) for the exact evidence.

### Resolved blocker: viewer slice `400`

The browser E2E initially failed on `GET /api/ai/series/{inputId}/slices/{index}` returning `400`.
Root cause, found by tracing the real request chain rather than guessing: `AiBackendService.
getSeriesSlice` validates every `inputId` against `INPUT_ID_PATTERN = ^inp_[0-9a-f]{32}$` -- the
real AI Module's own inputId shape -- **before** ever forwarding to the upstream AI service. The
mock's `deterministicInputId()` was generating a different shape (`mock-input-*`), so the Backend
rejected it outright; the mock's own `/inputs/{inputId}/slices/{index}` route (already correct)
was never even reached. Fixed by changing the mock to emit `inp_<32-hex>` -- still fully
deterministic (hashed from caseId+plane+filename+size), never a real medical UID. This was a mock
defect, not a Backend defect: the Backend's validation matches the real, already-tested contract
and was left untouched, per the Stage 87 rule that the mock adapts to the real contract, never the
other way around.

### Two pre-existing Stage 88 regression gaps found and fixed while restoring the full regression gate

Neither is related to the slice fix or to Stage 89's own scope -- both surfaced only because this
stage ran the *complete* `mvn test`/`npm test` suites (a prior offline-mode Maven invocation had
been silently short-circuiting on a missing dependency before ever reaching them):

- `StudyReportServiceTest` used a bare `new ObjectMapper()` to serialize a DTO containing an
  `Instant` field, which throws without the JSR-310 module registered. The real application's own
  Spring-managed `ObjectMapper` already has this module (proven by the passing `report.json`/
  `report.pdf` evidence throughout this stage) -- this was a test-only gap. Fixed by calling
  `.findAndRegisterModules()` in the test.
- `StudyReportControllerTest`'s PDF filename test built its request URL as a hand-percent-encoded
  literal string (`.../CASE:88%20unsafe/...`), which MockMvc's standalone dispatcher does not
  decode the same way a real servlet container does -- producing a literal `_20` in the asserted
  filename instead of the sanitized space. Fixed by using the URI-template form
  (`get("/api/studies/{caseId}/report.pdf", "CASE:88 unsafe")`), the standard, correct MockMvc
  pattern that matches real request decoding.
- One Frontend architectural-boundary test (`p9c4-canonical-closure-tests.mjs`, Stage 9C-4) blanket-asserted `StudyReviewView.tsx` never contains the string `schemaVersion` -- a rule written to
  stop the view from branching on the *multiplanar-run* contract version. Stage 88 legitimately
  added a read-only display of the *structured report's own* `schemaVersion` (`pfi.study-report.v1`,
  an unrelated contract) in the new report panel. Narrowed the assertion to allow that specific,
  non-conditional display while keeping the original protection (no `if`/`switch`/comparison on
  `schemaVersion` anywhere, and the other five files remain fully forbidden from the string
  entirely).

## Inventory

Already deterministic before Stage 89:

- Docker stack: Postgres, Backend, Frontend, mock AI, mock LLM.
- Demo auth: `POST /api/auth/demo-doctor`.
- Per-plane upload path used by the real New Analysis drawer.
- Multiplanar run persistence.
- Product analysis persistence.
- Professional review and correction endpoints.
- Subject list/history and measurement-series endpoints.
- Longitudinal comparison.
- Structured report JSON/PDF.
- Contextual chat persistence.

Previously manual/state-dependent:

- The database had to contain compatible studies before the browser demo.
- Historical Studies A/B were not reset by a one-command demo flow.
- The mock AI returned one generic product measurement, not case-specific demo values.
- The mock LLM returned one generic answer, not the defense-flow longitudinal answer.

## Demo Dataset

All data is synthetic and de-identified:

- `DEMO-PAT-001`
  - `DEMO-LUMBAR-2026-01`, 2026-02-01, majorAxisMm 11.9 mm.
  - `DEMO-LUMBAR-2026-02`, 2026-05-10, majorAxisMm 12.3 mm.
  - `DEMO-LUMBAR-2026-03`, 2026-08-25, automatic majorAxisMm 13.1 mm, professional correction 12.9 mm.
- `DEMO-PAT-002`
  - `DEMO-LUMBAR-ALT-01`, 2026-08-20, minimal alternate patient for list context.

Expected effective evolution:

`11.9 -> 12.3 -> 12.9`

Expected Study2 -> Study3 delta:

approximately `+0.6 mm`.

No clinical interpretation is attached to this difference.

## Scripts

PowerShell is the supported Stage 89 environment.

- `scripts/post-e50-demo-start.ps1`
  - starts the isolated demo compose stack.
- `scripts/post-e50-demo-stop.ps1`
  - stops only the isolated demo stack.
- `scripts/post-e50-demo-status.ps1`
  - prints service status.
- `scripts/post-e50-demo-reset.ps1`
  - stops the isolated demo stack, removes only the isolated demo volume, starts the stack, and runs the seed.
- `scripts/post-e50-demo-seed.mjs`
  - idempotently seeds the demo fixture through real Backend APIs.

The demo compose override uses:

- containers named `pfi-post-e50-demo-*`;
- network `pfi-post-e50-demo`;
- volume `post-e50-demo-postgres-data`.

It does not touch `pfi-freeze-runtime`, does not prune Docker, and does not remove non-demo resources.

## Mock Behavior

Mock AI:

- `/inputs`: deterministic inputId (`inp_<32-hex>`, matching the real AI Module's own contract and
  `AiBackendService.getSeriesSlice`'s validation) hashed from caseId, plane, filename, and file size.
- `/inputs/{inputId}/slices/{index}`: deterministic PNG bytes for the multi-slice viewer, for any
  inputId (stateless -- no existence check).
- `/v2/multiplanar/run`: deterministic run IDs from caseId and input IDs.
- `/v1/product/analyze`: deterministic product result by caseId.
- Keeps `status=partial`, `humanReviewRequired=true`, `notClinicalDiagnosis=true`.
- Keeps 67C experimental and P10.7 blocked states visible.

Mock LLM:

- OpenAI-compatible `/v1/chat/completions`.
- Deterministic answer for "¿Qué cambió respecto del estudio anterior?".
- Mentions only measurement values and professional correction.
- Does not say improved/worsened or recommend treatment.

## Preload vs Live

Recommended preload:

- Historical Study A.
- Historical Study B.
- Current Study C with seeded correction, for a safe fallback.

Recommended live actions:

- Open `/pacientes`.
- Open current Study C.
- Show AI partial result.
- Show review/correction persistence.
- Show evolution and Study B vs Study C comparison.
- Open structured report and export PDF/JSON.
- Ask the contextual assistant the suggested longitudinal question.

## Failure Plan

- If mock AI is unavailable, use already persisted analysis from the seeded fixture.
- If mock LLM is unavailable, continue with patients, review, correction, evolution, and report.
- If browser refresh happens, persisted data remains available.
- If PDF export fails, JSON export remains the structured fallback.
- Do not hide failures during defense; explain which local service is unavailable.

## Clinical Language

Allowed vocabulary:

- measurement
- difference
- professional review
- automatic result
- corrected value
- experimental
- blocked

Avoid:

- confirmed diagnosis
- improved
- worsened
- treatment recommended
