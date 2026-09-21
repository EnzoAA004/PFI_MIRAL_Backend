# Stage 81 — Post-E50 Product API Freeze (Release Candidate contract)

Branch: `research/post-e50-spring-ai-product-contract`. Base commit `dc8ed8c` (Stage 80). All shapes below are copied verbatim from real request/response bodies captured during the Stage-81 Docker E2E run against the fresh `docker-compose.post-e50.yml` stack (real Postgres, real Spring Boot, deterministic mock AI/LLM) — not hand-written examples. Every endpoint is under `/api/studies/{caseId}/...` unless noted.

This document freezes the contract as of Stage 81. A future stage changing any of these shapes must update this document in the same change.

## 1. Product analysis

### `POST /api/studies/{caseId}/product-analysis`
Auth: any authenticated principal (not gated by `requireProfessional`).
Request:
```json
{ "sagittalInputId": "input-sag-A-e2e81" }
```
Response `200`:
```json
{
  "analysisRunId": "uuid", "studyId": "uuid", "caseId": "CASE-81-A",
  "status": "completed|partial|failed",
  "warnings": ["67B2_RUNTIME_PARITY_NOT_PROVEN", "67C_RUNTIME_PARITY_NOT_PROVEN"],
  "discLevels": [{
    "discLevelId": "uuid", "level": "L4-L5",
    "localizationStatus": "localized", "localizationValidated": true, "abstentionPreserved": false,
    "axialPairingStatus": "experimental", "axialPairingValidated": false, "axialPairingExperimental": true, "axialPairingReviewRequired": true,
    "degenerativeFindingsStatus": "blocked_preprocessing_parity", "automaticInferenceEnabled": false,
    "measurements": [{ "measurementId": "uuid", "plane": "sagittal", "level": "L4-L5", "structure": "disc", "areaMm2": 12.3, "majorAxisMm": 12.3, "minorAxisMm": 8.4, "reviewRequired": true, "corrections": [] }],
    "review": { "required": true, "status": "pending", "comment": null, "reviewedBy": null, "reviewedAt": null }
  }],
  "review": { "required": true, "status": "pending", "comment": null, "reviewedBy": null, "reviewedAt": null },
  "humanReviewRequired": true, "notClinicalDiagnosis": true, "autonomousDiagnosis": false,
  "createdAt": "iso-instant", "completedAt": "iso-instant"
}
```
Errors: `400` invalid input; `404` Study not found; `502`/`504` AI upstream unavailable/timeout.

### `GET /api/studies/{caseId}/product-analyses` — list of run summaries for the Study.
### `GET /api/studies/{caseId}/product-analyses/{analysisId}` — same shape as the POST response above. `404` if `analysisId` belongs to a different Study (IDOR-safe).

## 2. Professional review / corrections (Stage 78)

### `PATCH /api/studies/{caseId}/product-analyses/{analysisId}/review`
Auth: `requireProfessional` (REVIEWER|DOCTOR|ADMIN).
Request: `{"status":"accepted|observed|rejected","comment":"optional"}` — field is `status`, not `decision`.
Response `200`: full analysis result (§1 shape) with `review.status`/`review.reviewedBy`/`review.reviewedAt` populated. `reviewedBy` is always derived server-side from the JWT, never from the request body.

### `PATCH /api/studies/{caseId}/product-analyses/{analysisId}/disc-levels/{discLevelId}/review`
Same request/response shape, scoped to one `discLevel.review` instead of the run-level `review`.

### `POST /api/studies/{caseId}/product-analyses/{analysisId}/measurements/{measurementId}/corrections`
Auth: `requireProfessional`.
Request: `{"field":"areaMm2|majorAxisMm|minorAxisMm","correctedValue":12.9,"comment":"optional"}` — `unit` is never accepted, derived server-side from `field`.
Response `200`: full analysis result with the new correction appended to that measurement's `corrections[]` (append-only — `originalValue` is always the AI's own value, never the previous correction).

## 3. Longitudinal comparison (Stage 79)

### `GET /api/studies/{caseId}/longitudinal-comparison?baselineCaseId=...`
Auth: any authenticated principal. `caseId` = current Study, `baselineCaseId` query param = the Study to compare against.
Response `200`:
```json
{
  "baselineCaseId": "CASE-81-A", "currentCaseId": "CASE-81-B",
  "baselineStudyId": "uuid", "currentStudyId": "uuid",
  "baselineAnalysisRunId": "uuid", "currentAnalysisRunId": "uuid",
  "baselineStudyDate": "2026-05-10", "currentStudyDate": "2026-08-25", "generatedAt": "iso-instant",
  "measurements": [{
    "level": "L4-L5", "plane": "sagittal", "structure": "disc", "metric": "areaMm2", "unit": "mm2",
    "baseline": { "automaticValue": 12.3, "correctedValue": null, "effectiveValue": 12.3, "source": "ai_original" },
    "current":  { "automaticValue": 12.3, "correctedValue": 12.9, "effectiveValue": 12.9, "source": "professional_correction" },
    "deltaAbsolute": 0.5999999999999996, "deltaPercent": 4.878048780487801, "trend": "increased",
    "comparisonStatus": "comparable"
  }]
}
```
Errors: `404` either Study not found, or the two Studies don't share a `subjectRef` (deliberately indistinguishable from "doesn't exist" — cross-patient IDOR convention); `400` `LONGITUDINAL_SAME_STUDY`; `422` `LONGITUDINAL_NO_USABLE_ANALYSIS_{BASELINE|CURRENT}`.

## 4. Contextual chat (Stage 80/81)

### `POST /api/studies/{caseId}/chat`
Auth: any authenticated principal.
Request:
```json
{ "question": "string, required, non-blank, max 2000 chars", "analysisId": "optional", "baselineCaseId": "optional", "conversationId": "optional (Stage 81-C)" }
```
`conversationId` omitted/`null` starts a new conversation; a valid existing one continues it (validated to belong to `caseId`, else `404 CHAT_CONVERSATION_NOT_FOUND`).
Response `200`:
```json
{
  "answer": "string", "conversationId": "uuid", "messageId": "uuid",
  "model": "Qwen2.5-7B-Instruct", "provider": "self-hosted", "promptVersion": "pfi.chat-system-prompt.v1",
  "generatedAt": "iso-instant", "warnings": ["..."],
  "humanReviewRequired": true, "notClinicalDiagnosis": true,
  "contextSources": ["current_analysis", "professional_review", "professional_correction", "longitudinal_comparison"]
}
```
Errors: `400` `CHAT_QUESTION_REQUIRED`/`CHAT_QUESTION_TOO_LONG`; `404` Study or `conversationId` not found/not owned; `503` `LLM_PROVIDER_DISABLED` (config-disabled); `502` `LLM_PROVIDER_UNAVAILABLE`/`LLM_CONTRACT_VIOLATION`; `504` `LLM_PROVIDER_TIMEOUT`.

### `GET /api/studies/{caseId}/chat-conversations`
Response `200`: `{"caseId":"...","conversations":[{"conversationId":"uuid","createdAt":"iso-instant","messageCount":4}]}`.

### `GET /api/studies/{caseId}/chat-conversations/{conversationId}`
Response `200`: `{"conversationId":"uuid","caseId":"...","createdAt":"iso-instant","messages":[{"id":"uuid","role":"USER|ASSISTANT","content":"...","createdAt":"iso-instant","model":null|"...","provider":null|"...","promptVersion":"pfi.chat-system-prompt.v1"}]}`. `404` if the conversation doesn't belong to `caseId`.

## 5. Diagnostics (Stage 81-T addition)

### `GET /api/system/health` — public, `{"status":"ok"}` only.
### `GET /api/system/diagnostics` — `requireAdmin`. New `llm` field as of Stage 81:
```json
"llm": { "enabled": true, "status": "ok|unavailable", "provider": "self-hosted", "model": "Qwen2.5-7B-Instruct" }
```
or `{"enabled": false, "status": "disabled"}` when `pfi.llm.enabled=false`. Never includes the LLM base URL. `llm.status` never factors into the top-level `status` field — an unreachable-but-enabled LLM never marks the whole Backend `degraded`.

## 6. Cross-cutting conventions (apply to every endpoint above)

- Every error response: `{"status":"error","code":"...","message":"...","traceId":"...","path":"...","method":"...","timestamp":"...","category":"...","retryable":bool,"humanReviewRequired":true,"notClinicalDiagnosis":true}` — never a raw stack trace.
- A resource belonging to a different Study/patient always 404s, never 403 (cross-Study/cross-patient IDOR convention, consistent since Stage 76).
- `reviewedBy`/actor identity is always derived from the JWT server-side, never trusted from the request body.
- `humanReviewRequired`/`notClinicalDiagnosis` are `true` on every response body that carries them, unconditionally.
