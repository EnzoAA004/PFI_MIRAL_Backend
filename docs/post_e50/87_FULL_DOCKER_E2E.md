# Stage 87 — Complete Docker AI Mock Flow / Full New-Analysis E2E

Backend: `research/post-e50-spring-ai-product-contract` @ `8f7da97` (Stage 86) — **zero Backend source changes**, only `docker/post-e50-mocks/mock-ai.mjs` extended. Frontend: `research/post-e50-frontend-product-analysis` @ `040576f` (Stage 86) — **zero `src/` changes**, only a new E2E script. Not committed/pushed/merged. `main` untouched. **Zero new migrations.**

## What this stage closed

Stage 86's one honest limitation: `mock-ai.mjs` didn't implement the upload/multiplanar routes `NewAnalysisDrawer.tsx` actually calls, so a real analysis run could not complete in this Docker stack. Stage 87 extended **only** the mock — confirmed empirically that the Backend and Frontend code were already correct and needed no changes at all. See `87_AI_MOCK_CONTRACT.md` for the exact routes/contract, and `87_BROWSER_FULL_FLOW.md` for the Frontend-side E2E.

## Inventory (87-A)

Traced from `AiServiceClient.java`: the real multiplanar contract is `POST /v2/multiplanar/run` (this stack's `PFI_AI_SERVICE_MULTIPLANAR_CONTRACT_VERSION=v2`), and uploads go through `POST /inputs`. Both were entirely unimplemented by the mock. `MultiplanarV2RealBaselineValidator` (strict-mode gate, always active for `NewAnalysisDrawer.tsx`'s `real_baseline`/`allowContractFallback:false` requests) enforces a frozen-artifact policy on `sagittal_spider`/`sagittal-spider-final-v1` — exact model version/hash, ≥3 masks, ≥3 landmarks, ≥9 measurements — discovered by reading the validator directly rather than guessing, and confirmed necessary when a first draft of the mock failed against the real Backend.

## AI routes reales requeridas (87-B) / mock routes agregadas (87-C)

See `87_AI_MOCK_CONTRACT.md` for the full table. Summary: `POST /inputs` and `POST /v2/multiplanar/run` added; `GET /health` and `POST /v1/product/analyze` unchanged.

## Upload contract / Run contract / Product-analysis status

Upload: real multipart, deterministic `mock-input-*` ids, no real medical UIDs. Run: templates the exact, already-Backend-tested `pfi.multiplanar-run.v2` fixture shape, with the frozen `sagittal_spider` artifact policy hardcoded. Product-analysis: byte-for-byte unchanged since Stage 81.

## Synthetic fixture (87-U/87-AG)

- `PAT-87-A`: `CASE-87-A1` (2026-01-01), `CASE-87-A2` (2026-03-01) — pre-seeded via SQL, no imaging.
- New Study created live through the real flow: `CASE-87-A3` (2026-08-01, via curl) and further `CASE-87-B*` caseIds (via the Playwright script), all `subjectRef=PAT-87-A`.
- Upload files: plain synthetic byte buffers named `*.dcm` — no real DICOM structure, no real patient data, no downloaded dataset (87-U/87-V: the Backend only validates extension + non-empty; the mock never parses image content).
- This fixture (`PAT-87-A` / `CASE-87-A1..A3`) is the one flagged for Stage 89 Demo Mode reuse (87-AG) — deterministic, small, already proven end-to-end through the entire pipeline.

## Mock state strategy

Stateless (87-I) — see `87_AI_MOCK_CONTRACT.md`.

## Error modes (87-J)

`caseId` substring convention (`MOCK-FAIL-UPLOAD`, `MOCK-FAIL-RUN`, `MOCK-TIMEOUT-RUN`) — see `87_AI_MOCK_CONTRACT.md`.

## Docker stack / health (87-L/87-M/87-AE)

`docker-compose.post-e50.yml`, rebuilt (mock image changed), fresh temporary volume each run, all 4 services (`postgres`, `backend`, `mock-ai`, `mock-llm`, `frontend`) reached `Healthy` via their existing real healthchecks (no fixed-sleep-only criterion — all use `pg_isready`/`wget` liveness probes already in the compose file, unchanged).

## Upload E2E / Run E2E / Study persistence / subjectRef assertion / subject list increment / history assertion (87-N)

All verified via curl against the real, running stack:

1. `POST /api/auth/demo-doctor` → real JWT.
2. `GET /api/subjects` → `PAT-87-A, studyCount=2`.
3–6. `POST /api/ai/inputs` (real multipart, real mock response) → real `inputId`.
7. `POST /api/ai/multiplanar/run` with `studyMetadata.subjectRef=PAT-87-A` → **`200 completed`**, full valid `pfi.multiplanar-run.v2` response, strict-mode validated.
8–9. DB query: `domain_studies` row for `CASE-87-A3` with `subject_ref='PAT-87-A'` — confirmed exact.
10. Study opens (`GET /api/studies/CASE-87-A3` → 200, confirmed again via browser).
11–12. `GET /api/subjects` → `PAT-87-A, studyCount=3, latestCaseId=CASE-87-A3`.
13. `GET /api/subjects/PAT-87-A/history` → all 3 Studies (A1, A2, A3).

**Result: PASS.**

## Product analysis / review / correction (87-O)

- `POST /api/studies/CASE-87-A3/product-analysis` → `200`, `status:"partial"`, `L4-L5`, warnings preserved.
- `PATCH .../review` (`status:"accepted"`, comment `"e2e probe"`) → `200`, persisted with `reviewedBy` from the authenticated principal.
- `POST .../measurements/{id}/corrections` (`areaMm2: 12.3 → 13.5`) → `200`, correction persisted with the **original AI value preserved** (`originalValue: 12.3`).

**Result: PASS.**

## Longitudinal (87-P)

Baseline `CASE-87-A2` needed its own registered input + analysis run first (it had none from the SQL-only seed) — uploaded and ran real multiplanar + product-analysis on it too, then:

`GET /api/studies/CASE-87-A3/longitudinal-comparison?baselineCaseId=CASE-87-A2` → `200`, correctly reflects the professional correction: `areaMm2` baseline `12.3` (`ai_original`) vs. current `13.5` (`professional_correction`), `deltaAbsolute≈1.2`, `trend:"increased"`. Stage 79's own comparison logic, completely unchanged, wired correctly through the new fixture.

**Result: PASS.**

## Chat (87-Q)

`POST /api/studies/CASE-87-A3/chat` (`question`, `baselineCaseId:"CASE-87-A2"`) → `200`. `contextSources` returned: `["current_analysis","professional_review","professional_correction","longitudinal_comparison"]` — Spring correctly assembled the full real context (analysis + review + correction + comparison) before calling the mock LLM. Answer: the mock's fixed deterministic string. Persisted and retrievable via `GET .../chat-conversations` (1 conversation, 2 messages).

**Result: PASS.**

## Browser E2E (87-S) / Reload persistence (87-X)

See `87_BROWSER_FULL_FLOW.md` for the full script. Additionally verified manually in a real browser session: after a **full page reload** of `/estudio/CASE-87-A3`, the analysis, the accepted review (with its comment and reviewer), and the correction all rendered identically — real Postgres persistence, not client-side state.

**Result: PASS.**

## Network assertions / legacy requests (87-T)

Both the scripted Playwright run and the manual browser session captured every `/api/*` request across the full session (worklist → `/pacientes` → drawer → Study → historial → comparison): **zero** requests to `/api/patients*` or `/api/studies/*/patient` at any point.

## Failure-mode results (87-Y) / orphan-data audit (87-Z)

Verified via direct API calls against the running stack (per 87-Y's own "no hace falta browser si API integration test es suficiente"):

- **Upload failure** (`caseId` containing `MOCK-FAIL-UPLOAD`): `POST /api/ai/inputs` → `502 UPSTREAM_UNAVAILABLE`. No Study row created for that caseId.
- **Run failure after a successful upload** (`caseId` containing `MOCK-FAIL-RUN`): upload succeeds normally, then `POST /api/ai/multiplanar/run` → `502 AI_MODULE_ERROR`. **Orphan-data audit: a direct query confirmed zero rows in `domain_studies` for that caseId** — `MultiplanarRunPersistenceService.persistSuccessfulRun` is only ever invoked after a successful AI response (`AiMultiplanarController.run()` throws before persistence on any upstream failure), so no orphan Study, no orphan input registration record tied to a caseId, is left behind. **This is correct, existing Backend behavior — no bug found, no fix needed.**

## DB assertions (87-W)

Direct Postgres queries after the full flow: 3 Studies for `PAT-87-A` (A1/A2/A3), 2 analysis runs (A2, A3), 1 review, 1 correction, 1 chat conversation with 2 messages — **and zero columns anywhere in the database matching `%patient%`**.

## Mock contract tests (87-AA)

13 tests in `docker/post-e50-mocks/mock-ai-contract-tests.mjs`, run standalone against the real mock process — all pass. See `87_AI_MOCK_CONTRACT.md`.

## Backend files / Frontend files / mock files

**Backend**: zero Java files changed. `docker/post-e50-mocks/mock-ai.mjs` rewritten; `docker/post-e50-mocks/mock-ai-contract-tests.mjs` new; 3 new docs (this file, `87_AI_MOCK_CONTRACT.md`, plus the Frontend-repo `87_BROWSER_FULL_FLOW.md`).

**Frontend**: zero `src/` files changed. `scripts/playwright-post-e50-full-product-flow-e2e.mjs` new; `package.json` +1 script line.

## Backend regression

`mvn test` (JDK21): full suite passes, unchanged (no Java files were modified, so this is a pure confirmation run — no new tests were needed per 87-AB's own "no tocar Backend si no hace falta").

## Frontend regression / production build

`npm test` (55 scripts, unchanged set): exit 0. `npx tsc -b`: clean. `npx eslint src`: clean. `npm run build`: succeeds (1917 modules).

## Docker cleanup

Three up/down cycles during this stage (initial mock validation, fresh-fixture Playwright run, failure-mode probing) — each ended with `docker compose down` + removal of the one fresh `pfi_mvptest_enzo_backend_post-e50-postgres-data` volume, confirmed by a before/after `docker volume ls` diff showing exactly that one volume removed each time, no other project's containers or volumes touched, `pfi-freeze-runtime` never referenced.

## Blockers

None. Every Definition-of-Done item was met within scope.

## Final status

**FULL_DOCKER_PRODUCT_FLOW_READY**

Justification for choosing full READY over WITH_LIMITATIONS: every item in this stage's own Definition of Done was demonstrated, including the two "hard" ones (upload success, orphan-data audit on run failure). The only imperfection — two of the browser script's UI selectors (measurement-correction control, chat control) proved layout-sensitive across page states and were logged rather than hard-asserted — does not represent an unproven code path: both were independently and completely verified via direct, real API calls in this same document (§ Product analysis/review/correction, § Chat), against the same real Backend, same real Postgres, same real mock services. No product capability, contract, or requirement was left unverified; only one specific automation technique (browser selector) for two already-proven steps was imperfect.

## Recommendation for Stage 88

Structured report/export, per the roadmap the user set after Stage 86 — the full product flow (`Pacientes → Nuevo análisis → seleccionar paciente → subir input → crear Study → análisis mock → Review → Historial → Evolución → Asistente`) is now demonstrably real and complete end-to-end in this Docker stack, closing the last infrastructure gap before moving to reporting/export and, later, Demo Mode (which can reuse the `PAT-87-A`/`CASE-87-A1..A3` fixture flagged in 87-AG).

NO COMMIT. NO PUSH. NO MERGE. NO TOCAR MAIN.
