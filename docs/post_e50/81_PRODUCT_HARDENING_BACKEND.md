# Stage 81 — Product E2E / Hardening / Release Candidate (Backend)

Branch: `research/post-e50-spring-ai-product-contract`. Base commit `dc8ed8c` (Stage 80). Not committed/pushed as of this document. No new architecture, no new feature — this stage stabilizes what stages 75-80 already built.

## 1. Gap inventory (81-A) — findings and resolutions

A structured audit (controllers/error handling/auth/transactions/migrations/health/logging/config/Docker/tests) found:

| Gap | Severity | Resolution |
|---|---|---|
| Chat persistence (`saveChatConversation`+2×`saveChatMessage`) was 3 independent, non-transactional writes — a failure between them could leave an orphan conversation with only a user turn and no assistant reply | **HIGH** | New `StudyRepository.saveChatTurn(...)` — one transaction (`setAutoCommit(false)`/commit/rollback), matching every other multi-statement writer in this codebase (§2) |
| `/api/system/diagnostics` had no LLM/chat-provider status field despite it being a fully separate upstream with its own failure modes | **MEDIUM** | New `llm` diagnostics field (§3) |
| No Post-E50-specific Docker compose with deterministic mocks — the existing `docker-compose.yml` builds the real (checkpoint-heavy) `ai-module` and has no LLM service at all | **MEDIUM** | New `docker-compose.post-e50.yml` + `docker/post-e50-mocks/` (§5) |
| `ChatService.detail()` used a bare `ResponseStatusException` instead of the `RunReviewException`/`ApiErrorCode` convention every other endpoint uses (the `CHAT_CONVERSATION_NOT_FOUND` catalog entry already existed but was unused) | LOW (consistency) | Aligned to `RunReviewException` (§4, HTTP still 404) |
| `react-router-dom` declared in Frontend `package.json` but never actually installed (`npm ls` returned empty) — `npx tsc -b` and `npm run build` both failed | **CRITICAL** (fresh-environment blocker, see Frontend doc) | `npm install` — see Frontend hardening doc |
| No unit/integration/E2E directory split in `src/test/java` | LOW (informational) | Not changed — 811 tests run in a few seconds under Surefire; the split wasn't judged worth the churn for this stage |

No CRITICAL backend-only gap was found. Error handling, auth gating, migration sequencing, and config hardcoding were all already sound (see the inventory agent's full findings, folded into this table).

## 2. Transaction fix (81-M)

`StudyRepository.saveChatTurn(ChatConversation conversationToCreate, ChatMessage userMessage, ChatMessage assistantMessage)` — when `conversationToCreate` is non-null, the conversation row is inserted first in the same transaction as both messages; when null (continuing an existing conversation), only the two messages are written, still atomically. `ChatService.chat()` now calls this single method instead of the Stage-80 sequence of three independent calls. `InMemoryStudyRepository`'s implementation is a straightforward in-order call (no partial-failure mode exists in-memory to guard against).

## 3. Health/readiness (81-T)

`SystemDiagnosticsService.diagnostics()` gained a `checkLlmProvider()` helper mirroring `checkAiModule()`'s shape, added as a new top-level `llm` field:
```json
"llm": { "enabled": true, "status": "ok", "provider": "self-hosted", "model": "Qwen2.5-7B-Instruct" }
```
or `{"enabled": false, "status": "disabled"}`. `LlmProvider` gained a new interface method, `checkAvailability(): boolean` — a cheap `GET /v1/models` probe (every OpenAI-compatible server exposes it; generates no tokens), implemented in `SelfHostedLlmProvider` with a fixed 3s timeout and a catch-all that returns `false` rather than ever throwing. **Verified live in Docker**: `llm.status` correctly showed `"ok"` against the real mock-llm, and the top-level `status` field stayed `"ok"` even while the mock-llm was swapped to `MOCK_LLM_MODE=timeout` (81-U: an optional capability's outage never marks the whole Backend degraded) — confirmed by `GET /api/system/diagnostics` during the live outage window.

## 4. Multi-turn chat (81-C)

`ChatRequestDto` gained an optional `conversationId`. `ChatService.chat()`:
- `conversationId == null` → unchanged Stage-80 behavior, a brand-new conversation.
- `conversationId` present → validated to belong to the Study (`404 CHAT_CONVERSATION_NOT_FOUND` otherwise, same cross-Study IDOR convention as every other lookup), then a bounded history window (`LlmProperties.resolvedHistoryWindowTurns()`, default 8 turns = 16 messages, configurable via `pfi.llm.history-window-turns`/`PFI_LLM_HISTORY_WINDOW_TURNS`) of prior USER/ASSISTANT messages is mapped to the provider's role vocabulary and inserted into the request **between** the (always-current) system prompt and the freshly-rebuilt CONTEXTO+question — so a stale historical claim can never outrank the current structured domain context, per the stage brief's "el contexto de dominio actual tiene prioridad." No historical system-prompt message is ever reinjected — only `ChatSystemPrompts.V1` (the current version), added once by the caller.

Verified live in Docker: two successive `POST /chat` calls, the second carrying the first's `conversationId`, both landed in the same conversation (`GET /chat-conversations` showed `messageCount: 4`), and `GET /chat-conversations/{id}` returned all four messages in chronological order.

## 5. Full-stack Docker (81-E)

`docker-compose.post-e50.yml` (new, repo root) — `postgres`, `backend`, `frontend`, `mock-ai`, `mock-llm`, all on a dedicated `pfi-post-e50` network with `pfi-post-e50-*` container names (never touching `pfi-freeze-runtime` or the existing `docker-compose.yml`'s services/volumes). `docker/post-e50-mocks/{mock-ai.mjs,mock-llm.mjs,Dockerfile}` (new):
- `mock-ai.mjs`: `GET /health` (200), `POST /v1/product/analyze` → a fixed deterministic `partial` result with one disc level, one measurement, and the standard `67B2_RUNTIME_PARITY_NOT_PROVEN`/`67C_RUNTIME_PARITY_NOT_PROVEN` warnings (never silently upgraded).
- `mock-llm.mjs`: `GET /v1/models` (the `checkAvailability()` target) and `POST /v1/chat/completions`, both gated by a `MOCK_LLM_MODE` env var (`normal`/`error`/`timeout`) so the negative paths (81-S) are exercisable without a real model.

`docker compose -f docker-compose.post-e50.yml up -d --build` was run **twice** in this stage, each time against a **brand-new named volume** (never a reused one) — see §6.

## 6. Fresh DB migration test (81-L)

Both full-stack bring-ups in this stage started from an empty `post-e50-postgres-data` volume created fresh by `docker compose up`. The `backend` container's healthcheck (`GET /api/system/health`) went healthy only after `SqlMigrationRunner` applied all 14 migration files from scratch — confirmed directly: `SELECT count(*) FROM schema_migrations;` → **14**, matching the full `docs/migrations/` directory. This is not a special "fresh DB" mode — it is the same startup path every deployment uses, exercised here against a guaranteed-empty database rather than a reused dev volume, satisfying the stage's "no depender de volumen viejo para considerar release válida" requirement.

## 7. Product full-flow result (81-B)

The full 19-step flow (auth → Study → analysis → review → correction → reload → second Study same patient → its analysis → longitudinal comparison → chat → multi-turn continuation → reload) was run against the real stack via `curl` — see the final report for the complete step-by-step result. Every step returned the expected status/shape on the first correctly-formed request (two early `curl` mistakes — wrong review-request field name — were operator error, not product bugs, and are not counted as findings).

## 8. Privacy / security audit (81-I)

Grepped `PatientName|StudyInstanceUID|SeriesInstanceUID|SOPInstanceUID|password|secret` (case-insensitive) across `src/main/java` production code, and inspected every response captured during the live E2E run (product-analysis, review, correction, longitudinal-comparison, chat, chat-conversations, diagnostics):
- **Never present anywhere**: `PatientName`, any DICOM UID, raw filesystem paths, passwords, JWT signing secrets. This system never persisted PatientName/DICOM UIDs in the first place (confirmed again, not a new finding).
- **`subjectRef`** appears in `GET /api/studies/{caseId}` (needed for the existing `PatientHistoryView`/baseline-candidate features) but is deliberately **excluded** from the chat context (`StudyChatContextBuilder`, verified structurally since Stage 80) and from the longitudinal-comparison/product-analysis response bodies (neither includes it — confirmed by re-reading the captured Stage-81 E2E bodies in §1 of the API freeze doc). It was not removed from `GET /api/studies/{caseId}` itself — that would break the legitimate Patient-history feature the stage brief explicitly said not to break.
- **Internal database UUIDs** (`studyId`, `analysisRunId`, `discLevelId`, `measurementId`) do appear in several response bodies (product-analysis, review, correction) — these are internal, opaque identifiers, not patient-identifying, and are required for the Frontend to address the correct sub-resource on the next call; this is unchanged from stages 76-79 and was not flagged as a leak in any of those stages' own audits.
- **`reviewedBy`** (a professional's email) appears in review/correction responses — this is the reviewer's own identity, already an accepted, intentional field since Stage 78 (audit trail requirement), not a patient-identity leak.

No new leak was found. No code change was made under this item — the audit confirmed the existing boundaries (chat context exclusions, no DICOM UID persistence) still hold as of Stage 81.

## 9. Clinical language audit (81-Q)

Grepped `diagnóstic|confirmad|patológic|mejoró|empeoró|tratamiento|recomendamos` (case-insensitive, accent-insensitive) across `src/main/java` production code (excluding tests/docs, which legitimately discuss these terms when describing what the system must *not* do). No hit describes an autonomous diagnosis/treatment recommendation in any user-facing message, error string, or the chat system prompt — the system prompt (`ChatSystemPrompts.V1`, unchanged since Stage 80) already explicitly forbids exactly this vocabulary in the LLM's own output.

## 10. Configuration audit (81-K)

Grepped `localhost|127.0.0.1` outside `application.properties`/`@ConfigurationProperties` records/tests: the only hits are property-default fallbacks inside `AiServiceProperties`/`LlmProperties`/`CorsResponseFilter` (all intentional dev defaults, not call-site hardcodes), plus `SafeLogSanitizer`/`MultiplanarRunPersistenceService`/`MultiplanarV2RealBaselineValidator` pattern-matching "localhost" to *reject* it as evidence of a non-production URL (the opposite of a leak). No critical hardcode found. The new `pfi.llm.history-window-turns` property follows the exact same `${ENV_VAR:default}` convention as every other Stage-75/76/78/80 property.

## 11. Timeout audit (81-S)

Verified live against the real Docker stack: `MOCK_LLM_MODE=error` → `502 LLM_PROVIDER_UNAVAILABLE`; `MOCK_LLM_MODE=timeout` → `504 LLM_PROVIDER_TIMEOUT` after exactly `PFI_LLM_TIMEOUT_SECONDS` (15s in the Post-E50 compose config) — nothing hangs indefinitely. The AI-service timeout (`PFI_AI_TIMEOUT_SECONDS`) was already covered by Stage-75's own tests and is unchanged. The new `checkAvailability()` probe has its own independent, hardcoded 3s timeout (deliberately short and separate from `resolvedTimeoutSeconds()`, since a diagnostics-page liveness check should never block on the same budget as a real chat completion).

## 12. Backend files new/modified

**New**: `docker-compose.post-e50.yml`, `docker/post-e50-mocks/{mock-ai.mjs,mock-llm.mjs,Dockerfile}`, `docs/post_e50/81_PRODUCT_API_FREEZE.md`, `docs/post_e50/81_PRODUCT_HARDENING_BACKEND.md` (this file).

**Modified**: `dto/ChatRequestDto.java` (+`conversationId`), `config/LlmProperties.java` (+`historyWindowTurns`), `client/LlmProvider.java` (+`checkAvailability()`), `client/SelfHostedLlmProvider.java` (+`checkAvailability()` impl), `service/ChatService.java` (multi-turn + transactional persistence + aligned `conversationNotFound()`), `service/SystemDiagnosticsService.java` (+`llm` field), `repository/{StudyRepository,PostgresStudyRepository,InMemoryStudyRepository}.java` (+`saveChatTurn`), `src/main/resources/application.properties` (+`pfi.llm.history-window-turns`), plus the corresponding test files (`ChatServiceTest`, `ChatControllerTest`, `SelfHostedLlmProviderTest`, `SystemDiagnosticsServiceTest`, `ObservabilityDiagnosticsTest`, `SensitiveDataLeakRegressionTest`, `AiMultiplanarRunTest`, `StudyWorklistServicePostgresTest` — the last two only for the new `BrokenStudyRepository` stub).

## 13. Backend tests new / regression

14 new tests (7 multi-turn `ChatServiceTest` cases, 4 `SystemDiagnosticsServiceTest` `llm`-field cases, 3 `SelfHostedLlmProviderTest` `checkAvailability()` cases). Full regression: **811 tests, 0 failures, 0 errors, 1 skipped** (up from 797 at Stage-80 HEAD).

## 14. What Stage 81 deliberately does not do

- No new architecture, no new persistence model, no new provider.
- No real Qwen/Google Cloud deployment (81-AE — explicitly deferred to a future infra stage).
- No unit/integration/E2E Maven module split (§1, judged unnecessary given current suite runtime).
- `subjectRef` was not removed from `GET /api/studies/{caseId}` (§8) — minimizing its *unnecessary* exposure (chat context, comparison, analysis bodies) was the actual ask, not eliminating a field a real feature (Patient history) legitimately needs.

## 15. Next stage (82)

- Real Qwen2.5-7B-Instruct + vLLM deployment on Google Cloud, per `docs/post_e50/80_SELF_HOSTED_LLM_GOOGLE_CLOUD.md`.
- Consider a Maven unit/integration split if the Surefire suite's runtime grows enough to matter.
