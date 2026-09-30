# Stage 81 — Post-E50 Product Release Candidate

Backend: `research/post-e50-spring-ai-product-contract` @ `dc8ed8c` (+ Stage-81 uncommitted changes). Frontend: `research/post-e50-frontend-product-analysis` @ `bd4c281` (+ Stage-81 uncommitted changes). Not committed/pushed/merged as of this document. `main` untouched. No new branches created.

## Architecture (unchanged since Stage 80, now stabilized)

```
React (Frontend)
  |
Spring Boot (Backend)
  |-- PostgreSQL (raw JDBC, migration-driven schema)
  |-- AI Product Client -> FastAPI AI Module (or mock, per environment)
  `-- LlmProvider -> Self-hosted LLM service (or mock, per environment)
```

## Product flow covered end-to-end (81-B)

Study creation → analysis (mock AI) → persistence → professional review → measurement correction → reload → second Study, same patient → its own analysis → longitudinal comparison → contextual chat (grounded in the comparison + correction) → multi-turn continuation → chat history reload — all 19 steps of the stage brief's worked example were executed against a real, freshly-provisioned Docker stack (`docker-compose.post-e50.yml`) and passed. See the Backend/Frontend hardening docs for exact request/response bodies and the final report for the full step list.

## Docker

`docker-compose.post-e50.yml` (new) — `postgres` + `backend` + `frontend` + `mock-ai` + `mock-llm`, isolated network/volume/container names (`pfi-post-e50-*`), never touching the existing `docker-compose.yml` or any frozen-demo resource. Brought up **twice** in this stage, each time from a brand-new named volume (fresh-DB migration test, §Backend doc). All three product Docker images (backend, frontend, mocks) build cleanly from a clean `docker build`.

## Browser E2E

First real-backend browser E2E in this project's post-e50 history: `scripts/playwright-post-e50-real-backend-e2e.mjs` (Frontend repo), reusing Playwright against the live stack — 5/5 checks passed (page renders, real data displayed, Evolución/Asistente sections present, a real question gets a real answer through the whole stack, no unexpected console errors).

## Security / privacy

No PatientName/DICOM UID/password/secret found anywhere in code, logs, or the live-captured API responses. `subjectRef` remains scoped to where it's legitimately needed (Study detail, Patient history) and stays excluded from the chat context/analysis/comparison bodies, unchanged since Stage 80's original design. No new leak surface introduced by Stage 81's own changes (multi-turn chat, history selector, LLM health field) — the LLM base URL is never exposed by the new `/api/system/diagnostics` `llm` field, mirroring the existing AI-module base-URL-omission precedent.

## API freeze

`docs/post_e50/81_PRODUCT_API_FREEZE.md` — 10 endpoints across product-analysis, review/correction, longitudinal comparison, and chat, every shape copied verbatim from real captured Stage-81 E2E traffic.

## Configuration

No critical hardcode found. New config surface: `pfi.llm.history-window-turns` (`PFI_LLM_HISTORY_WINDOW_TURNS`, default 8), following the exact same env-override convention as every other property since Stage 75.

## Known limitations (81-X)

- 67B2 runtime parity not proven.
- 67C runtime parity not proven / not validated.
- P10.7 automatic degenerative-findings inference blocked by preprocessing parity.
- Real Qwen2.5-7B-Instruct is not deployed — the self-hosted LLM path is exercised only against a deterministic mock (`mock-llm.mjs`) in Docker E2E, per this stage's own explicit "no Qwen real todavía."
- All AI/LLM results require human professional review; nothing in this product is a standalone/autonomous clinical diagnosis.
- This is a non-diagnostic research prototype.
- No unit/integration/E2E Maven module split exists (informational, not a functional gap).
- The real-backend Playwright E2E script is manual-run only, not wired into CI.

## Regression results

- **Backend** (JDK21, `mvn test`): 811 tests, 0 failures, 0 errors, 1 skipped.
- **Frontend** (`npm test`): exit 0, no `not ok` lines.
- **Frontend typecheck** (`npx tsc -b`): clean (the two pre-existing `react-router-dom` errors are now resolved).
- **Frontend lint** (`npx eslint`): clean on all Stage-80/81 files.
- **Frontend production build** (`npm run build`): succeeds, `dist/` produced.
- **Docker builds**: backend, frontend, and mocks images all build cleanly.
- **Fresh-DB migrations**: all 14 migration files applied cleanly to two independent brand-new Postgres volumes in this stage.

## Release status

**RELEASE_CANDIDATE_READY_WITH_KNOWN_LIMITATIONS**

All CRITICAL and HIGH gaps found during this stage's own audit were fixed within the stage (missing frontend dependency, non-transactional chat persistence, missing LLM health signal, race-prone baseline selector, duplicate-ID a11y bug). The remaining items in "Known limitations" above are pre-existing, explicitly out-of-scope-for-this-stage conditions (real LLM deployment, 67B2/67C/P10.7 runtime parity) that this project has consistently and honestly carried forward since stages 66-80 — none are new regressions introduced by Stage 81, and none block the product's already-functioning capabilities (Study → analysis → review → correction → comparison → chat) from working end-to-end today.

## Recommendation for Stage 82

Real Qwen2.5-7B-Instruct + vLLM deployment on Google Cloud, per the target architecture already documented in `docs/post_e50/80_SELF_HOSTED_LLM_GOOGLE_CLOUD.md` — the `LlmProvider` abstraction and OpenAI-compatible contract are confirmed ready for this swap without touching `ChatService` or anything above it.
