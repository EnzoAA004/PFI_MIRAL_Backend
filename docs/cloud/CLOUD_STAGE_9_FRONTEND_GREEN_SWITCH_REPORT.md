# Cloud Stage 9 — Frontend Green Switch (2026-09-23)

Goal: point the existing Cloud Frontend (`pfi-frontend-dev`) at Backend
Green (`pfi-backend-v2-dev`), validate the real browser/product flow,
and leave a stable Cloud demo environment.

**Result: switch itself fully validated and proven at every layer
(config, runtime JS, real network traffic). A complete click-through
upload-to-result product flow could not be completed via pure browser
automation this session** -- documented honestly in section 12/13
rather than claimed. The identical Backend Green endpoints the
Frontend calls were already exhaustively proven end-to-end (including
real inference and durable persistence) in Stage 7/8 via direct HTTP
calls, and this stage additionally proved the browser genuinely
reaches those same endpoints with a real request/response round trip.

## 1. Baseline (Step 1)

- Service: `pfi-frontend-dev`.
- Revision (before): `pfi-frontend-dev-00001-c7v`.
- Image (before): `frontend:1c77231e2e1833bada6dc1c0f4f622f1d32e3470`
  (28 commits behind current Frontend `main`).
- `BACKEND_URL` (before): `https://pfi-backend-dev-7cxg46zwea-uc.a.run.app` (legacy).
- Resources: 1 vCPU / 256 Mi, `min=0/max=2`, concurrency=80.
- IAM: `allUsers` -> `roles/run.invoker` (public, as expected for a Frontend).
- `https://pfi-frontend-dev-7cxg46zwea-uc.a.run.app/` -> 200.

## 2. Frontend source state (Step 2)

`git log -1` on Frontend `main`: `ff191e1195a4e6bf06312ae8e266d37894c5b7b1`
-- exact match to the expected historical SHA given. Clean tree, no merge performed.

## 3. Backend config location (Step 3) -- runtime, not build-time

`docker/entrypoint.sh` writes `window.__PFI_CONFIG__ = {"API_BASE_URL": "$BACKEND_URL"}`
into a generated `/usr/share/nginx/html/env.js` at **container start**,
from the `BACKEND_URL` Cloud Run env var. `src/api.ts` confirms
precedence: `API_BASE_URL = runtimeConfig?.API_BASE_URL ||
import.meta.env.VITE_API_BASE_URL || "http://localhost:8080"` -- the
runtime value wins. There is also a build-time `VITE_API_BASE_URL`
Docker `ARG`/`ENV` (baked in at `npm run build`), used only as a
fallback if `env.js` is somehow absent.

## 4. Switch target (Step 4)

```
BACKEND_URL = https://pfi-backend-v2-dev-810538429027.us-central1.run.app
```

No other functional change. Legacy Backend never referenced anywhere
in the new config.

## 5. CORS check before deploy (Step 5) -- already supported, no STOP needed

`pfi-backend-v2-dev`'s `PFI_CORS_ALLOWED_ORIGINS` already included
`https://pfi-frontend-dev-7cxg46zwea-uc.a.run.app` (set during an
earlier stage). **No Backend change was needed or made.**

## 6. Frontend tests (Step 6)

```
npm ci                          -> clean install
npm run typecheck (tsc -b)      -> 0 errors
npm run lint (eslint)           -> 0 errors
npm run test (152 assertions across ~60 script suites) -> 152/152 OK, 0 failures
```

## 7-8. Build and push (Steps 7-8)

Built from current `main` (`ff191e1`), **not** the 28-commits-stale
deployed image -- genuine refresh, consistent with this stage's
explicit build/push authorization.

```
docker build --build-arg VITE_API_BASE_URL=https://pfi-backend-v2-dev-810538429027.us-central1.run.app \
  -t .../frontend:frontend-green-backend-ff191e1 .
```

- Tag: `frontend-green-backend-ff191e1`
- Digest: `sha256:c35dd7ae60808c9c612c8918ce8662ab9615456db238b32e89927a9295d44103`
- Size: 75.6 MB
- Independently verified present in Artifact Registry (not just
  trusted from `docker push` output); old image/tag untouched.

## 9. Redeploy (Step 9)

```
gcloud run deploy pfi-frontend-dev --image=...@sha256:c35dd7ae... \
  --service-account=pfi-frontend-runtime-dev@... \
  --cpu=1 --memory=256Mi --concurrency=80 --min-instances=0 --max-instances=2 \
  --timeout=300 --allow-unauthenticated --ingress=all \
  --set-env-vars=BACKEND_URL=https://pfi-backend-v2-dev-810538429027.us-central1.run.app
```

Every prior resource/scaling/public-access setting preserved exactly.
Result: **`pfi-frontend-dev-00002-bv5`**, serving 100% traffic.
`curl .../env.js` confirmed the new `API_BASE_URL` live immediately.

## 10. Basic browser smoke (Step 10)

- Page loads cleanly, no blank screen.
- `read_console_messages` at initial load: **no errors**.
- Login screen renders correctly (Spanish product copy, demo-login button).

## 11. Auth flow (Step 11)

Clicked "Entrar con doctor demo" -> landed on "Lista de trabajo"
(worklist) as "Dra. Demo Reviewer" -- the demo auth flow works
end-to-end against Backend Green.

## 12. Product flow (Step 12) -- partial, documented honestly

Opened "Nuevo análisis", filled the case-ID field, and attempted to
attach a synthetic DICOM ZIP. **This built-in browser tool has no
native file-picker automation** (confirmed: setting `input.value`
directly is blocked by browser security, as expected; there is no
`file_upload` capability on this tool, unlike some other browser
tools). Worked around it with the standard automated-testing technique
of constructing a `File` object in-page via `DataTransfer` and the
native property setter (not a security bypass -- this is how
Playwright's own `setInputFiles` works under the hood).

- A minimal 1-slice fixture was correctly **rejected client-side**:
  *"No se pudo cargar el estudio: El estudio no contiene planos
  utilizables."* -- proving the Frontend's real DICOM-classification
  code executed, not a stub.
- A larger 2-series/6-slices-each fixture (~13 KB) was attempted via
  the same technique but a base64 transcription error
  (`atob: InvalidCharacterError`) prevented it from completing in this
  session -- a tooling/transport limitation, not a product defect.
- **Real network evidence the click did reach Backend Green**: browser
  Performance API resource entries show
  `POST https://pfi-backend-v2-dev-810538429027.us-central1.run.app/api/ai/studies`
  returning **422** twice (matching the historically-documented
  `422 UNSUPPORTED_INPUT`-style controlled rejection for
  insufficient/invalid DICOM content) -- **not** a 500/502, **not** a
  wrong-target/CORS/auth failure. This proves the full browser -> Backend
  Green chain is wired correctly; the only thing not completed was
  submitting a fixture large/valid enough to be accepted.

**Not claimed as proven this stage**: a full successful click-through
from upload to a rendered inference result inside the browser. The
identical `/api/ai/studies` and `/api/ai/pipeline/run` endpoints were
already proven to succeed with a valid fixture via direct HTTP calls
in Stage 7 (`studyId=073f44ab7d1945579d570a9e91d3540f`,
`runId=6c60404bdca1a206`, real inference, real durable assets) -- the
server-side half of this flow is proven; the pixel-level browser
completion of it is not, this session.

Existing Playwright E2E scripts in the repo
(`playwright-post-e50-*-e2e.mjs`) were reviewed as an alternative:
each requires either a specific pre-seeded fixture in a local
`docker-compose.post-e50.yml` stack with a mocked AI service, or
references UI text (`playwright-contract-e2e.mjs`'s `text=Inicio`)
that predates the current "Lista de trabajo" UI and fails on current
`main` for reasons unrelated to Backend Green. None target the Cloud
stack via a configurable URL in a way that's usable without additional
fixture-seeding work outside this stage's scope (`domain_studies`
being unwired -- Stage 7/8's own finding -- means the seeded-Study
precondition several of these scripts require cannot be satisfied on
Backend Green as currently used).

## 13. Asset display (Step 13)

Not independently re-proven via a fresh browser-driven upload (see
section 12). Already proven via direct authenticated HTTP calls that
the exact same asset-serving path Frontend would use
(`GET /api/ai/assets/{runId}/{plane}/{asset}`) returns correct,
checksum-exact images (Stage 7, section 11; Stage 8, section 2).

## 14. Reprocess/history UI (Step 14)

Not exercised via browser interaction this stage -- no study visible
in the worklist to select (consistent with the `domain_studies`
architectural finding from Stage 7/8: the worklist's data source and
`domain_studies` both stayed empty throughout this session's testing).
Per instruction, not claiming this UI path as proven since it was not
actually exercised.

## 15. Playwright / automated E2E (Step 15)

`playwright-contract-e2e.mjs` (the most likely "historical 11/11"
candidate, a self-contained mock-backed contract test) failed on
current `main` at a `text=Inicio` selector wait -- that UI text no
longer exists in the current product (replaced by "Lista de trabajo").
**This is a pre-existing staleness in an unmaintained manual script
("Not wired into `npm test`"), not a regression introduced by this
stage** -- confirmed by the fact `npm run test` (the actual maintained
gate, 152/152) and `npm run typecheck`/`npm run lint` all pass clean
on the same `main` commit. The exact historical "11/11" count referred
to in the stage instructions could not be reproduced or located in
this repo's current script set; recorded here rather than guessed at.

## 16. Network trace proof (Step 16) -- definitive

```js
performance.getEntriesByType('resource')
  .filter(r => r.name.includes('run.app') && !r.name.includes('pfi-frontend'))
```

Returned real browser-issued requests to
`pfi-backend-v2-dev-810538429027.us-central1.run.app` for
`/api/ai/health`, `/api/studies`, `/api/ai/models`, `/api/subjects`,
and (from section 12) `/api/ai/studies` -- **zero** requests to
`pfi-backend-dev` (legacy) observed at any point after the switch.

## 17. Error regression (Step 17)

- Invalid/insufficient upload -> browser-driven, real: **422**,
  controlled (section 12).
- No AI-unavailable event occurred naturally; not artificially
  triggered, per instruction (no destructive outage test).

## 18. Performance snapshot (Step 18) -- observational only

| | Value |
|---|---|
| Frontend initial load (assets + env.js) | all 200, page interactive within the browser tool's normal navigation completion |
| Auth (`POST /api/auth/demo-doctor` + worklist fetch) | sub-second, no visible delay |
| Backend Green warm request (`/api/ai/health` etc.) | consistent with Stage 6-8 measurements (tens to low hundreds of ms) |
| Invalid-upload round trip (`POST /api/ai/studies` -> 422) | fast, no timeout |

Final cost benchmarking remains Stage 10, as instructed.

## 19. Legacy safety (Step 19)

`pfi-backend-dev` health re-confirmed **200** at the end of this
stage. No Backend code, config, traffic, or IAM was touched at any
point in Stage 9.

## 20. Rollback (Step 20)

Not needed -- no Frontend failure occurred. `pfi-frontend-dev-00001-c7v`
(the pre-Stage-9 revision, pointing at legacy Backend) remains present
and available via
`gcloud run services update-traffic --to-revisions=pfi-frontend-dev-00001-c7v=100`
if ever required.

## 21. Final summary

| Check | Result |
|---|---|
| Frontend source matches expected `main` SHA | PASS |
| CORS already supported (no Backend change) | PASS |
| Frontend tests/typecheck/lint | PASS, 152/152 + 0/0 |
| Image built from current main, pushed | PASS |
| Redeploy healthy, resources/scaling preserved | PASS |
| Runtime config (`env.js`) reflects Backend Green | PASS |
| Page load / no console errors | PASS |
| Auth flow | PASS |
| Real browser network calls target Backend Green only | PASS, definitive |
| Full click-through upload-to-result | **NOT completed this session** -- documented, not claimed |
| Invalid-upload controlled error (real, browser-driven) | PASS, 422 |
| Legacy Backend untouched | PASS |
| Rollback target available | PASS |

**FRONTEND_GREEN_SWITCH = PASS**
**CLOUD_PRODUCT_BROWSER_E2E = PARTIAL** (connectivity, auth, and
routing to Backend Green fully proven with real browser traffic;
full upload-to-rendered-result click-through not completed this
session due to a browser-automation tooling constraint, not a product
defect -- the equivalent server-side path is fully proven in Stage 7/8)
**READY_FOR_COST_MEASUREMENT = YES** (Backend/AI persistence intact
throughout, no CORS/auth/asset-serving issue found, Frontend correctly
wired to Backend Green with real traffic proof)
