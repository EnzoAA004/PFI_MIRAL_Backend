# Stage 80 — Self-hosted LLM deployment on Google Cloud

This document describes a target deployment for the self-hosted LLM behind `SelfHostedLlmProvider` (`pfi.llm.base-url`). It is a deployment target, not something built or provisioned in this stage — no cloud resources were created, no real model weights were downloaded, and the Docker E2E (see backend/frontend docs) exercised a mock HTTP server standing in for this deployment, never a real Qwen instance.

## 1. Model

**Qwen2.5-7B-Instruct** (Apache-2.0 license, permitting self-hosting and commercial use without a per-request fee to a model vendor). Chosen for: an open, permissively-licensed weight release; an instruction-tuned variant suited to answering questions over a provided context rather than open-ended generation; a 7B parameter size that fits on a single mid-range GPU (see §4) without multi-GPU sharding; and native support in vLLM's OpenAI-compatible server, which is the same HTTP contract `SelfHostedLlmProvider` already speaks (`/v1/chat/completions`), so no custom protocol adapter is needed.

This is a **target model**, not a hard dependency — `LlmProperties.model` is a configuration value, and `LlmProvider` has no Qwen-specific code path. A future stage could point the same `SelfHostedLlmProvider` at a different self-hosted model (or add `OpenAiLlmProvider`/`GeminiLlmProvider`/`AnthropicLlmProvider` implementations, per the interface design in the backend doc) without changing `ChatService` or anything above it.

## 2. Weight storage — Cloud Storage

Model weights are stored in a Cloud Storage bucket, e.g. `gs://<project>-pfi-models/conversational/qwen2.5-7b-instruct/`, downloaded once from the model's official Hugging Face repository and re-uploaded to this bucket under the project's own control (so serving does not depend on Hugging Face's availability at request time, and so the exact weight snapshot used is pinned rather than "whatever the tag currently resolves to"). The serving VM (§3) pulls the weights from this bucket at boot (`gsutil -m cp -r gs://.../qwen2.5-7b-instruct /mnt/models/` or a Cloud Storage FUSE mount) rather than embedding them in a container image — this keeps the serving image small and lets the weight snapshot be updated independently of the image.

**Weights are never committed to Git** (explicit stage-80 "NO HACER" rule) — the bucket, not the repository, is the source of truth for the model artifact.

## 3. Serving — vLLM

vLLM's OpenAI-compatible server:
```
python -m vllm.entrypoints.openai.api_server \
  --model /mnt/models/qwen2.5-7b-instruct \
  --host 0.0.0.0 --port 8000 \
  --max-model-len 8192
```
This is the same response shape (`choices[0].message.content`) that `SelfHostedLlmProvider.parseProviderResponse` already parses defensively (backend doc §4) — no custom serving code is needed on the Spring side beyond pointing `pfi.llm.base-url` at this server's internal address.

## 4. Compute — Compute Engine, CPU vs GPU tradeoff

A 7B-parameter model at typical inference precision (fp16/bf16, or an int8/int4 quantized variant if latency/cost pressure warrants it) is impractical to serve interactively on CPU alone — expect order-of-magnitude slower per-token latency than GPU serving, likely unacceptable for a chat UI where a clinician is waiting on an answer. The realistic target is a **Compute Engine VM with a single GPU** sized to fit the model plus its KV cache (an L4 or T4-class GPU is a common minimum for a 7B model at fp16; a more capable GPU reduces latency and allows a larger `--max-model-len`/concurrent-request count). This document does not state exact GPU-hour or storage pricing — those change over time and vary by region/commitment type; the tradeoff to evaluate at provisioning time is GPU class × sustained-use/committed-use discount × expected request volume against a plain on-demand VM, using Google Cloud's own current pricing calculator rather than a number fixed in this document.

A Vertex AI managed-endpoint deployment of the same model is a viable alternative to a self-managed Compute Engine VM — it trades operational control (patching, exact vLLM version, custom flags) for less infrastructure to manage. This document assumes the Compute Engine + vLLM path since it gives `SelfHostedLlmProvider` a plain HTTP endpoint with no additional Google-specific client/auth integration, keeping the "no vendor lock-in" property (§7) straightforward; the Vertex path remains available as a future option without changing anything on the Spring side beyond the base URL and whatever auth header that path requires.

## 5. Networking

The LLM server should **not** be exposed on a public IP. It sits on an internal VPC network reachable only from the Spring Boot backend's own network (a Compute Engine internal IP, a VPC-native GKE cluster, or a Cloud Run service with VPC connector — whichever hosts the backend in a given environment), matching the existing pattern for the FastAPI product-analysis service (`pfi.ai.base-url` is likewise never a public internet endpoint in a production deployment). If the Backend and the LLM VM are in the same VPC, an internal DNS name or static internal IP is sufficient for `pfi.llm.base-url`; no public load balancer or Cloud Armor policy is needed for this service specifically.

## 6. Secrets

The self-hosted server described here does not itself require an API key (it is an internal-network-only, unauthenticated-by-network-boundary endpoint, mirroring the same trust model already used for the FastAPI product-analysis service). If a future provider swap introduces a hosted API (OpenAI/Gemini/Anthropic), its API key would be stored in Secret Manager and injected into the Spring Boot environment the same way `PFI_AI_SERVICE_URL`-adjacent secrets already are — never committed to `application.properties` or Git, consistent with this project's existing secret-handling convention.

## 7. No vendor lock-in

- The `LlmProvider` interface (backend doc §2) is the seam: `SelfHostedLlmProvider` today, `OpenAiLlmProvider`/`GeminiLlmProvider`/`AnthropicLlmProvider` as later, additive implementations selected by `pfi.llm.provider` — `ChatService` and everything above it is unaware of which one is active.
- vLLM's OpenAI-compatible API means the *wire protocol* itself is already the same one a hosted OpenAI-compatible provider would use, so `SelfHostedLlmProvider`'s request/response handling would need minimal change even to point at a different OpenAI-compatible server.
- The model weights are Apache-2.0 licensed and stored in the project's own Cloud Storage bucket (§2) — self-hosting has no per-request licensing dependency on Google or any model vendor.
- Nothing in this design assumes a Google-specific SDK/client library in the request path — it is plain HTTP, so the same `SelfHostedLlmProvider` code would work against an equivalent self-hosted deployment on any other cloud or on-prem hardware, changing only `pfi.llm.base-url`.

## 8. Scaling

A single GPU VM has a finite concurrent-request capacity (bounded by GPU memory for the KV cache across simultaneous requests). For a low-volume MVP this is likely sufficient; scaling beyond one instance means either a managed instance group behind an internal load balancer (each instance running its own vLLM server) or a queueing layer in front of a fixed pool — both are standard Compute Engine patterns, not specific to this feature. `SelfHostedLlmProvider`'s own `pfi.llm.timeout-seconds` and Spring's existing retry-free "translate to 502/504, degrade gracefully" behavior (backend doc §4) already assumes the provider might be momentarily saturated or unavailable, so scaling out does not require a Backend code change — only a `pfi.llm.base-url` pointing at a load balancer instead of a single instance.

## 9. Shutdown / cost-control strategy

Since the LLM feature is optional (`pfi.llm.enabled=false` by default, backend doc §3) and GPU VM time is the dominant cost driver, the serving VM should be stoppable independently of the rest of the product: a scheduled Compute Engine start/stop (e.g., running only during expected usage windows) or an on-demand start triggered by an operator, with `PFI_LLM_ENABLED` flipped back to `false` (or the base URL simply unreachable) while the VM is stopped — the Backend's existing provider-unavailable handling (`502`) means a stopped LLM VM degrades the chat feature alone, never the rest of the product (§ backend doc "provider-unavailable/timeout must degrade gracefully"). This is a deliberate operational lever, not an automatic behavior built in this stage.

## 10. Explicitly not attempted in this stage

- No real Qwen2.5-7B-Instruct instance was deployed or smoke-tested against Google Cloud — the stage's own Definition of Done treats this as optional ("no es requisito de DONE"), and it is reported here as explicitly skipped, not fabricated.
- No Terraform/Deployment Manager/gcloud provisioning scripts were written — this document describes the target architecture for a future provisioning pass, not an executed one.
- No exact GPU-hour or storage pricing is quoted (§4) — current Google Cloud pricing should be checked at provisioning time rather than trusting a number written here.
