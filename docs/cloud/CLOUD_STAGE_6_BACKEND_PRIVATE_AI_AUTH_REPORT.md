# Cloud Stage 6 — Backend Green -> Private AI Auth (2026-09-23)

Goal: make Backend Green (`pfi-backend-v2-dev`) invoke the private
`pfi-ai-module` using Google identity / ID token authentication,
without making AI public. Auth + connectivity only -- no full DICOM
product E2E.

**Result: fully validated, config-only.** The Backend already had a
complete, fail-closed Google-ID-token auth implementation from an
earlier stage; Stage 6 was pure configuration + live proof, no Java
source changes.

## 1. Live baseline (Step 1) -- verified, not assumed

- Project: `pfi-asplanatti-fabrello-v1`, region `us-central1`.
- Backend Green health: **200**.
- AI authenticated health: **200**.
- AI unauthenticated: **403**.
- Legacy Backend (`pfi-backend-dev`): **200**, untouched throughout.

## 2. IAM check (Step 2)

`pfi-backend-runtime` has `roles/run.invoker` at **project level**
(confirmed via `gcloud projects get-iam-policy --filter`), not scoped
to `pfi-ai-module` specifically. `pfi-ai-module`'s own service-level
IAM policy has zero bindings (`{}`) -- the SA's invoke capability comes
entirely from the project-wide grant.

**No IAM change made this stage.** The project-wide grant already
functions correctly and satisfies the requirement; scoping it down to
a service-level binding on `pfi-ai-module` only, and removing the
broader project-level one, would be a genuine least-privilege
improvement, but the instructions explicitly said not to remove
working IAM without being sure it is safe, and adding a redundant
service-level grant is not clearly necessary either. **Recorded here
as a hardening recommendation for a future stage**, not acted on.

## 3. Canonical AI URL / audience (Step 3)

```
PFI_AI_SERVICE_URL      = https://pfi-ai-module-7cxg46zwea-uc.a.run.app
PFI_AI_SERVICE_AUDIENCE = https://pfi-ai-module-7cxg46zwea-uc.a.run.app
```

The stable service URL (`status.url`), not a tagged or revision URL,
confirmed via `gcloud run services describe pfi-ai-module`.

## 4. Backend AI client audit (Step 4) -- already implemented

Found a complete, mature implementation already in the Backend repo
(source `40592c7`, unchanged this stage):

- `AiServiceAuthorizationProvider` (interface) /
  `GoogleIdTokenAiServiceAuthorizationProvider` (ADC ->
  `IdTokenCredentials` with target audience, `refreshIfExpired()` on
  every call, throws `AiServiceAuthorizationException` on any
  failure -- never silently returns no header) /
  `NoopAiServiceAuthorizationProvider` (explicit local-only mode).
- `AiServiceAuthMode` enum (`NONE` / `GOOGLE_ID_TOKEN`), parsed
  strictly -- any other value throws.
- `AiServiceProperties.resolvedAudience()` -- validates the audience
  is an absolute HTTP(S) origin with no path/query/fragment, fails
  closed otherwise.
- `WebClientConfig.aiServiceAuthorizationFilter` -- attaches
  `Authorization: Bearer <token>` when the provider returns one;
  **explicitly removes** any incoming `Authorization` header first in
  both branches, so a caller's own JWT is never accidentally forwarded
  to the AI module.
- `SecurityStartupValidator.validateAiServiceAuthorization()` -- the
  fail-closed gate: in a "cloud/demo" runtime (production profile OR
  `PFI_AI_PRIVATE_REQUIRED=true`), `PFI_AI_AUTH_MODE` must be declared
  explicitly (an unset value used to silently fall back to Noop --
  exactly the failure mode Stage 6 was meant to prevent, already
  closed); if `PFI_AI_PRIVATE_REQUIRED=true`, mode must be
  `google-id-token`; in that mode, the audience must resolve to a
  valid origin or the Spring context refuses to start.
- No SA key files anywhere; no logging statements in the `config`
  package at all (grepped, zero matches) -- the token cannot leak via
  logs by construction.

**No Backend code changes were required or made.** Steps 5-7 (auth
design, implementation, fail-closed config) were already satisfied by
the existing code; Stage 6 was audit + configure + prove.

## 5. Tests (Step 8) -- already covered, re-run for confirmation

```
AiServiceAuthorizationFilterTest ....... 4/4
  - Authorization header attached (google mode)
  - none mode sends no Authorization, preserves trace header
  - frontend JWT never forwarded to AI module
  - failing provider stops the request before it goes out anonymous
AiServiceAuthorizationPropertiesTest ... 6/6
  - audience validation (origin-only, no path/query/fragment)
SecurityStartupValidatorTest ........... 24/24
  - fail-closed gates for jwt secret, auth enabled, demo disabled,
    CORS, and all AI-auth-mode/audience combinations
```

Full Backend suite: **1173/1173 passed, 0 failures, 0 errors**
(`mvn test`, historical baseline unchanged). No new tests were needed
since no code changed; existing coverage already maps onto spec items
A (header attached), B (audience), C (no token logged -- structurally
guaranteed, no log statements exist), D (missing config fails closed),
E (Noop only for explicit local/none mode), G (credentials reused via
`refreshIfExpired()`). Item F (401/403 handled cleanly) is covered by
existing `AiServiceClient` WireMock tests
(`AiServiceClientProductAnalyzeWireMockTest`,
`AiServiceClientV2WireMockContractTest`, etc.), all passing.

## 6. Direct IAM proof (Step 9) -- impersonation unavailable, used the real path instead

Attempted `gcloud auth print-identity-token
--impersonate-service-account=pfi-backend-runtime@...`: failed with
`IAM_PERMISSION_DENIED` (missing `iam.serviceAccounts.getAccessToken`
on that SA for my own user account). **Not granted** -- doing so would
itself be an unauthorized IAM broadening just to run a side-channel
test. Instead, the actual production code path (Backend's live ADC
inside Cloud Run, via the real redeploy in section 8) served as the
proof, which is arguably stronger evidence than local impersonation
since it exercises the exact runtime identity and code path that will
be used in practice.

## 7. Commit / build (Steps 10-11)

**None needed.** No Backend source changed; documented here instead of
a no-op commit.

## 8. Green config + redeploy (Steps 12-13)

```
gcloud run services update pfi-backend-v2-dev \
  --update-env-vars=PFI_AI_SERVICE_URL=https://pfi-ai-module-7cxg46zwea-uc.a.run.app,
                     PFI_AI_AUTH_MODE=google-id-token,
                     PFI_AI_SERVICE_AUDIENCE=https://pfi-ai-module-7cxg46zwea-uc.a.run.app,
                     PFI_AI_PRIVATE_REQUIRED=true
```

`--update-env-vars` only (additive), not `--set-env-vars`, so the
existing `DATABASE_URL`/`PFI_AUTH_JWT_SECRET` secret bindings and all
other env vars were left untouched. No new image (same digest
`sha256:4a6e404817e068bc568a583294ce9723d72b144e64f74d472e5884db0fa2e80d`
as the pre-Stage-6 baseline). Postgres, GCS, service account,
resources (1 vCPU/512Mi), `min=0/max=2`, `concurrency=80` all
preserved and confirmed unchanged in the resulting service spec.

Result: **`pfi-backend-v2-dev-00002-hdw`**, `Ready=True` /
`ConfigurationsReady=True` / `RoutesReady=True`. This deploy succeeding
is itself evidence the fail-closed `SecurityStartupValidator` accepted
the configuration -- a bad audience or an unset mode in this
`PFI_AI_PRIVATE_REQUIRED=true` runtime would have refused to start the
Spring context and the deploy would have failed.

Legacy `pfi-backend-dev`, Frontend, and AI service configuration were
not touched.

## 9. Backend health (Step 14)

```
GET /api/system/health            -> 200 {"status":"ok"}
GET /api/system/runtime-diagnostics -> 200
  revision: pfi-backend-v2-dev-00002-hdw
  persistence: postgres, postgresEnabled=true
  storage: gcs, gcsConfigured=true
  dataSource: HikariDataSource, totalConnections=1, activeConnections=0
```

DB pool healthy and unaffected by the config change.

## 10. Backend -> AI auth smoke (Step 15) -- PASS

Used the smallest existing real endpoint rather than inventing a
diagnostic one: `GET /api/ai/health`, gated only by Backend's normal
`AuthFilter` (any authenticated non-pending user), which internally
calls `AiServiceClient.health()` -> the private AI module.

```
POST /api/auth/demo-doctor -> demo bearer token (synthetic account, no PHI)
GET  /api/ai/health (Authorization: Bearer <demo token>)
  -> 200
  {"status":"ok","service":"pfi-ai-module","modelsConfigured":true,
   "outputStorageConfigured":true,"backendStatus":"up",
   "aiModuleAvailable":true,"degradedMode":false, ...}
```

Backend -> Google ID Token -> private AI -> 200, confirmed with a real
warm call.

## 11. AI log proof (Step 16)

Cloud Logging on `pfi-ai-module`, correlated by timestamp (no token
values present in any log line -- confirmed by inspection):

```
13:26:41.807Z  200  0.124176086s  GET /health
```

A `200` entry in Cloud Run's `httpRequest` log is only reachable after
the platform's own IAM check on the incoming ID token passes -- an
invalid or wrong-audience token would have been rejected at the
ingress with 401/403 before ever reaching the container, the same way
the unauthenticated case is (section 12). This is platform-level
confirmation the token was valid, not just an application-level
inference.

## 12. Unauthenticated AI result (Step 17) / Security gate (Step 21)

- Direct unauthenticated `GET https://pfi-ai-module-.../health` ->
  **403**, re-confirmed at the end of this stage.
- `gcloud run services get-iam-policy pfi-ai-module` -> `{}` (no
  bindings) both before and after every action this stage -- no
  `allUsers`, no `allAuthenticatedUsers`.
- No service-account JSON used anywhere (ADC only, both for the
  Backend's own runtime identity and for every `gcloud`/impersonation
  attempt in this session).
- No token value appears in any log, test output, or this report.

## 13. Cold invocation / scale-to-zero interaction (Steps 18-19) -- real finding

AI was allowed to idle to a genuine scale-to-zero (`min=0`), then one
real cold Backend -> AI call was made (single cycle, per instruction --
no repeated 15-minute waits):

| Call | Result | Elapsed (client) | Note |
|---|---|---|---|
| 1st (cold) | **200**, `degradedMode:true, aiModuleAvailable:false`, body says `502 BAD_GATEWAY ... no esta disponible temporalmente` | ~11.0s | Backend's own diagnostic-path timeout (10s) elapsed before AI's cold start finished |
| 2nd (immediate retry) | **200**, same degraded body | ~10.3s | AI was still mid-cold-start |
| 3rd (after AI settled) | **200**, full healthy payload, `aiModuleAvailable:true` | fast (<1s per AI-side log) | AI fully warm |

**Root cause of the degraded window, confirmed by reading the source**:
`AiServiceProperties.resolvedDiagnosticTimeoutSeconds()` deliberately
caps health/readiness/models-verify calls at `min(10,
resolvedTimeoutSeconds())` -- 10 seconds -- specifically so a hung AI
module never holds a request thread for the full 180s inference
timeout (see the comment in `AiServiceProperties.java`). AI's real
cold-start this cycle took from GCSFuse-mount-start
(`13:43:36.8`) to `Application startup complete`
(`13:44:17.09`) -- about 40s, well past that 10s diagnostic
ceiling.

**This is graceful degradation working as designed, not a bug**:
Backend never crashed, never returned an opaque 500, and self-healed
automatically once AI finished starting -- exactly the "controlled
error, not opaque crash" behavior Step 20 asked to verify. It is,
however, a real operational characteristic worth carrying into Stage 7
planning: a cold AI instance will show as `degradedMode:true` to
Backend callers for the first several seconds after a scale-from-zero
event on the diagnostic path specifically (inference calls use the
much longer 180s `timeout`, not the 10s `diagnosticTimeout`, so a real
DICOM pipeline run would not hit this ceiling the same way).

## 14. Error handling (Step 20)

Verified via the live cold-start sequence above (502-from-AI ->
controlled degraded 200, not a crash) and via the existing WireMock
test suite (`AiServiceClientProductAnalyzeWireMockTest`,
`AiServiceClientV2WireMockContractTest`,
`AiSubarticularPredictionWireMockTest`, etc. -- 401/403/404/5xx/timeout
scenarios already covered and passing in the 1173/1173 run). No
deliberate additional live destabilization of AI was performed, per
instruction.

## 15. Token / latency summary (Step 18, consolidated)

- First authenticated call after AI cold start: ~11.0s (bounded by the
  10s diagnostic timeout, not by token acquisition).
- Warm authenticated call: **0.117-0.124s** end-to-end (Backend ID
  token mint/refresh + AI response), per AI-side log latency.
- Token acquisition itself is not separately observable from outside
  the JVM, but `IdTokenCredentials.refreshIfExpired()` is a no-op on
  an unexpired cached token, consistent with the warm latency being
  dominated by the AI call itself, not token minting.

## 16. Rollback status

Not needed. `pfi-backend-v2-dev-00002-hdw` is healthy; the prior
revision `pfi-backend-v2-dev-00001-zx5` remains available as a
rollback target
(`gcloud run services update-traffic --to-revisions=pfi-backend-v2-dev-00001-zx5=100`)
if ever required. Legacy `pfi-backend-dev` was never touched.

## 17. Remaining full E2E work

This stage proves auth + connectivity only. Not yet exercised: a real
DICOM study upload through Backend Green triggering an actual AI
inference call (uses the 180s `timeout`, not the 10s
`diagnosticTimeout` measured here) and durable-asset persistence
through the full Backend -> AI -> GCS path end-to-end. That is Stage 7
(full Cloud E2E), not in scope here per explicit instruction.

## 18. Final summary

| Check | Result |
|---|---|
| Backend Green healthy pre/post | PASS |
| AI IAM audit | PASS -- project-wide grant works; scoping down recommended, not done |
| Canonical AI URL / audience | PASS |
| Auth implementation audit | PASS -- already complete, no code change |
| Backend tests | 1173/1173 PASS |
| Config-only Green update | PASS |
| Backend health post-deploy | PASS |
| Backend -> AI warm auth smoke | PASS, 200 |
| AI log proof | PASS, 200 correlated |
| Unauthenticated AI rejected | PASS, 403 |
| AI remains private | PASS, zero IAM bindings |
| Cold invocation behavior | PASS -- graceful degradation, no crash, self-heals |
| Error handling (401/403/5xx/timeout) | PASS, existing coverage confirmed |
| Token leakage check | PASS, none found |
| Rollback target available | PASS, untouched |

**BACKEND_PRIVATE_AI_AUTH_VALIDATED = YES**
**READY_FOR_FULL_CLOUD_E2E = YES**
