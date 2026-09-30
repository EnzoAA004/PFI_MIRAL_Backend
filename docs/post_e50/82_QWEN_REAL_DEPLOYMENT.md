# Stage 82 — Qwen2.5-7B-Instruct Real Deployment (Plan, Not Executed)

**Status of this document**: every command below is prepared, reviewed, and believed correct against real project state (verified via read-only `gcloud`/`gsutil` inspection on 2026-08-26) — **none has been executed**. Stage 82 in its current (planning-only) scope has an explicit USD $0 target for newly-created resources. This is the reproducible runbook for the *next* session that is authorized to actually spend.

## Prerequisites (verified, read-only, 2026-08-26)

- Project: `pfi-asplanatti-fabrello-v1`, billing enabled (account `012206-071130-92E8E1`, open).
- Region: `us-central1` (matches existing `pfi-ai-module` Cloud Run service and `pfi-containers` Artifact Registry repo — keeps everything same-region).
- **Blocker to resolve before step 3**: project-wide GPU quota `GPUS_ALL_REGIONS = 0`. A quota increase request for at least 1 GPU must be submitted (Cloud Console → IAM & Admin → Quotas → filter `GPUS_ALL_REGIONS` or `NVIDIA_L4_GPUS`, region `us-central1`) and **approved** before step 3 can succeed. This can be instant or take up to a few business days depending on Google's review — not something this session can force or predict.
- APIs already enabled (confirmed via `gcloud services list --enabled`): `compute.googleapis.com`, `artifactregistry.googleapis.com`, `storage.googleapis.com`, `logging.googleapis.com`, `monitoring.googleapis.com`. **Not yet enabled, needed for §6 (Secret Manager, only if an internal auth token is added later)**: `secretmanager.googleapis.com` — not required for the baseline plan (§9 explains why).
- Existing resources **not to be touched**: Cloud Run `pfi-ai-module`, bucket `gs://pfi-rm-lumbar-artifacts-2026-ef` (only a new prefix will be added under it, nothing existing modified), service accounts `pfi-training-vm`, `pfi-ai-runtime`, `pfi-backend-runtime`, Artifact Registry `pfi-containers` (only a possible new tag added, existing images untouched).

## 1. Storage (§82-E) — reuse existing bucket

No new bucket. Reuse `gs://pfi-rm-lumbar-artifacts-2026-ef`, new prefix, clearly separated from the existing imaging `models/` prefix:
```
gs://pfi-rm-lumbar-artifacts-2026-ef/conversational/qwen2.5-7b-instruct/
```
**Prepared, not executed** — first real download step (on a machine with enough disk, or directly on the GPU VM after boot):
```bash
# Download once from Hugging Face, verify, then upload to GCS for reproducible re-use across VM restarts:
huggingface-cli download Qwen/Qwen2.5-7B-Instruct --revision a09a35458c702b33eeacc393d103063234e8bc28 --local-dir ./qwen2.5-7b-instruct
sha256sum ./qwen2.5-7b-instruct/model-*.safetensors > ./qwen2.5-7b-instruct/SHA256SUMS.txt
gsutil -m cp -r ./qwen2.5-7b-instruct gs://pfi-rm-lumbar-artifacts-2026-ef/conversational/qwen2.5-7b-instruct/
```
This is a one-time ~15.24 GB transfer (§ cost doc §6). Weights are **never** committed to Git (82-AK) and **never** baked into the Docker image (82-E preference: no weights in the image).

## 2. Service account (§82-F) — minimal privilege

**Prepared, not executed**:
```bash
gcloud iam service-accounts create pfi-llm-runtime \
  --display-name="PFI LLM Runtime (Qwen/vLLM, read-only model access)"

# Read-only access to the model prefix only (not the whole bucket):
gsutil iam ch \
  serviceAccount:pfi-llm-runtime@pfi-asplanatti-fabrello-v1.iam.gserviceaccount.com:objectViewer \
  gs://pfi-rm-lumbar-artifacts-2026-ef

# Pull from Artifact Registry (only if a custom image is ever built, §9):
gcloud artifacts repositories add-iam-policy-binding pfi-containers \
  --location=us-central1 \
  --member="serviceAccount:pfi-llm-runtime@pfi-asplanatti-fabrello-v1.iam.gserviceaccount.com" \
  --role="roles/artifactregistry.reader"

# Logging/monitoring write (for the VM's own agent):
gcloud projects add-iam-policy-binding pfi-asplanatti-fabrello-v1 \
  --member="serviceAccount:pfi-llm-runtime@pfi-asplanatti-fabrello-v1.iam.gserviceaccount.com" \
  --role="roles/logging.logWriter"
gcloud projects add-iam-policy-binding pfi-asplanatti-fabrello-v1 \
  --member="serviceAccount:pfi-llm-runtime@pfi-asplanatti-fabrello-v1.iam.gserviceaccount.com" \
  --role="roles/monitoring.metricWriter"
```
No `roles/owner`, no `roles/editor`, no bucket-wide write access. This mirrors the existing project's own pattern (three purpose-scoped service accounts already exist: `pfi-training-vm`, `pfi-ai-runtime`, `pfi-backend-runtime` — this would be a fourth, following the same convention).

## 3. Network (§82-G) — no public exposure

**Design**: the VM gets **no external IP**; Spring Boot reaches it over the VPC's internal IP only. A firewall rule restricts port 8000 (vLLM) to internal traffic from wherever Spring Boot actually runs (Cloud Run egress via a Serverless VPC Access connector, or another Compute Engine VM in the same VPC/subnet).

**Prepared, not executed**:
```bash
# Firewall: allow port 8000 ONLY from the internal subnet range (10.128.0.0/20, us-central1's default subnet -- already exists, confirmed via `gcloud compute networks subnets list`), never 0.0.0.0/0.
gcloud compute firewall-rules create allow-internal-vllm \
  --network=default \
  --direction=INGRESS \
  --action=ALLOW \
  --rules=tcp:8000 \
  --source-ranges=10.128.0.0/20 \
  --description="Internal-only access to the self-hosted LLM (vLLM) service -- never public."
```
If Spring Boot's own deployment (Cloud Run) is not already VPC-connected, it needs a [Serverless VPC Access connector](https://cloud.google.com/vpc/docs/serverless-vpc-access) pointed at the same `default` network/`us-central1` subnet before it can reach the VM's internal IP — **documented here as a required step, not yet built**, since this project's current Backend deployment target (local Docker Compose, per Stages 80/81) doesn't yet run on Cloud Run itself.

## 4. VM + GPU (§82-C/82-D)

**Chosen**: Compute Engine VM, `g2-standard-4` (4 vCPU, 16 GB RAM, 1× NVIDIA L4, 24 GB VRAM) — see cost doc §2 for the full comparison against T4/CPU-only.

**Prepared, not executed**:
```bash
gcloud compute instances create pfi-llm-qwen \
  --zone=us-central1-a \
  --machine-type=g2-standard-4 \
  --accelerator=type=nvidia-l4,count=1 \
  --image-family=pytorch-2-9-cu129-ubuntu-2204-nvidia-580 \
  --image-project=deeplearning-platform-release \
  --boot-disk-size=60GB \
  --boot-disk-type=pd-balanced \
  --no-address \
  --service-account=pfi-llm-runtime@pfi-asplanatti-fabrello-v1.iam.gserviceaccount.com \
  --scopes=cloud-platform \
  --maintenance-policy=TERMINATE \
  --metadata=install-nvidia-driver=True \
  --labels=project=pfi,component=llm-runtime,stage=post-e50-82
```
- `--no-address`: no external IP (§3).
- `pytorch-2-9-cu129-ubuntu-2204-nvidia-580` (confirmed available via `gcloud compute images list --project=deeplearning-platform-release`, 2026-08-26): Google's Deep Learning VM image, NVIDIA drivers + CUDA pre-installed — avoids a separate manual driver-install step.
- `--boot-disk-size=60GB`: OS + Docker + vLLM + the 15.24 GB model (if cached locally, §5) with headroom.
- `--labels`: for cost tracking/filtering in billing reports (§82-Z).

## 5. Model boot/cache strategy (§82-I)

**Prepared, not executed** — a boot/startup approach that avoids re-downloading 15.24 GB on every restart:
```bash
# On first boot (or via a startup-script), sync from GCS to local disk cache once:
gsutil -m rsync -r gs://pfi-rm-lumbar-artifacts-2026-ef/conversational/qwen2.5-7b-instruct/ /mnt/models/qwen2.5-7b-instruct/
# On subsequent boots, rsync is a no-op if the local cache already matches -- fast, idempotent.
```

## 6. vLLM container (§82-H)

**Prepared, not executed** — pinned to the real, currently-latest vLLM release (verified via GitHub Releases API, 2026-08-26):
```bash
docker run -d --name pfi-vllm-qwen \
  --gpus all \
  -p 8000:8000 \
  -v /mnt/models/qwen2.5-7b-instruct:/models/qwen2.5-7b-instruct:ro \
  vllm/vllm-openai:v0.27.1 \
  --model /models/qwen2.5-7b-instruct \
  --served-model-name Qwen2.5-7B-Instruct \
  --host 0.0.0.0 \
  --port 8000 \
  --dtype auto \
  --max-model-len 8192
```
`--dtype auto` lets vLLM pick BF16 (the weights' native format, matching the VRAM sizing in the cost doc §3) rather than forcing a cast. `--max-model-len 8192` is the deliberate, product-justified reduction from the model's 131,072-token ceiling (cost doc §3). **Before real execution**: re-check `vllm/vllm-openai`'s Docker Hub tags to confirm `v0.27.1` is still current (a future session's "today" will have moved on) — this stage's own instruction is explicit about never copying an old command without re-verifying.

## 7. Health checks (§82-J)

vLLM's OpenAI-compatible server exposes `GET /health` (liveness) and `GET /v1/models` (used by this project's own `SelfHostedLlmProvider.checkAvailability()`, added in Stage 81 — no Spring contract change needed here, confirmed compatible). Prepared verification (not executed):
```bash
curl -sf http://<vm-internal-ip>:8000/health
curl -sf http://<vm-internal-ip>:8000/v1/models
```

## 8. Spring Boot configuration (§82-U)

No hardcoded URL in Java (already true since Stage 80 — `LlmProperties.baseUrl` is fully externalized). Prepared env vars for the Backend's real deployment target once the VM is up:
```bash
PFI_LLM_ENABLED=true
PFI_LLM_PROVIDER=self-hosted
PFI_LLM_BASE_URL=http://<vm-internal-ip>:8000
PFI_LLM_MODEL=Qwen2.5-7B-Instruct
PFI_LLM_TIMEOUT_SECONDS=90
PFI_LLM_TEMPERATURE=0.1
PFI_LLM_MAX_OUTPUT_TOKENS=512
PFI_LLM_HISTORY_WINDOW_TURNS=8
```
`<vm-internal-ip>` is deliberately not filled in here — it's assigned at instance-creation time and is exactly the kind of value this document's own rule (82-AF: "NO poner IPs privadas" in the frozen runtime manifest) says to keep out of any committed artifact; it belongs in the real deployment's own environment configuration, not in Git.

## 9. Smoke test sequence (§82-K through 82-Q) — to run once infra exists

1. **Direct vLLM smoke**: `POST http://<vm-internal-ip>:8000/v1/chat/completions` with `{"model":"Qwen2.5-7B-Instruct","messages":[{"role":"user","content":"Respondé únicamente: OK"}]}` — expect `200`, content `"OK"` (or close to it), record latency.
2. **Language-only smoke**: same endpoint, "Resume en una oración: el valor actual es 12.9 mm y el anterior 12.3 mm."
3. **Spring→Qwen smoke**: `PFI_LLM_ENABLED=true` pointed at the real VM, `POST /api/studies/{caseId}/chat` with `"¿Qué cambió respecto del estudio anterior?"` against the Stage-81 synthetic fixture (baseline 12.3, current 12.9, delta 0.6, professional correction) — verify the real system prompt (`ChatSystemPrompts.V1`, unchanged) is used, not a substitute.
4. **Safety/adversarial smoke** (82-M, exact 4 prompts from the stage brief) — documented as a safety smoke, explicitly not a clinical validation.
5. **Frontend→Backend→Qwen smoke** (82-W): open `ChatPanel`, ask a real question, see a real answer — reusing Stage 81's `scripts/playwright-post-e50-real-backend-e2e.mjs` pattern with `PFI_LLM_BASE_URL` pointed at the real VM instead of the mock.
6. **Failure-mode smoke** (82-X): stop the vLLM container (or the VM), confirm chat degrades to a controlled `502`/`504` while Study/analysis/review/comparison keep working — this is the same code path Stage 81 already proved against the mock, re-run here against the real absence of a real provider.
7. **Latency/concurrency smoke** (82-O/82-P): ~10 sequential requests (min/median/max), then 2 and 5 concurrent requests, watching for OOM/timeout/queueing.
8. **GPU observability** (82-Q): `nvidia-smi` output captured during the above, alongside `top`/`free -h` for CPU/RAM.

## 10. Shutdown (§82-R/82-S)

```bash
gcloud compute instances stop pfi-llm-qwen --zone=us-central1-a
gcloud compute instances describe pfi-llm-qwen --zone=us-central1-a --format="value(status)"  # expect TERMINATED
```
See `82_QWEN_OPERATIONS_RUNBOOK.md` for the full start/status/stop script trio and the "what's still billable after stop" checklist.

## Budget controls (§82-Z)

**Prepared, not executed** — a billing budget alert (notification only, does **not** stop resources: "alerta ≠ enforcement", per the stage brief):
```bash
gcloud billing budgets create \
  --billing-account=012206-071130-92E8E1 \
  --display-name="pfi-llm-stage82-budget" \
  --budget-amount=20USD \
  --threshold-rule=percent=0.5 \
  --threshold-rule=percent=0.9 \
  --threshold-rule=percent=1.0
```
A $20 budget with 50/90/100% alert thresholds gives generous headroom over the ~$1–3 expected smoke-test cost (cost doc §5) while still catching an accidentally-left-running VM well before it becomes expensive. This alert **notifies by email**; it does **not** stop the VM — only the manual/scripted stop in §10 does that.
