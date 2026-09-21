# Stage 82 — Qwen2.5-7B-Instruct on Google Cloud: Cost Analysis

**Date of price research**: 2026-08-26. **Region**: us-central1 (Iowa) — chosen to match this project's existing resources (Cloud Run `pfi-ai-module`, Artifact Registry `pfi-containers`, and the artifact bucket are all already in us-central1; keeping the LLM runtime in the same region avoids cross-region egress and latency). **Currency**: USD. **This document reflects planning research only — no resource was created.**

## Sources consulted

Google's own `cloud.google.com/compute/gpus-pricing` and `cloud.google.com/compute/vm-instance-pricing` pages render their pricing tables via JavaScript and could not be scraped as static text in this environment. The figures below are cross-checked across multiple pricing-aggregator sources that mirror Google's published list prices, plus this project's own read-only `gcloud`/`gsutil` inspection (region, quotas, storage class). **Before any real spend, re-verify these numbers on the interactive [Google Cloud Pricing Calculator](https://cloud.google.com/products/calculator)** — this document is a planning estimate, not an invoice.

- [cloudprice.net — g2-standard-4](https://cloudprice.net/gcp/compute/instances/g2-standard-4) (accessed 2026-08-26)
- [cloudprice.net — n1-standard-4](https://cloudprice.net/gcp/compute/instances/n1-standard-4) (accessed 2026-08-26)
- [cloudprice.net — n2-standard-8](https://cloudprice.net/gcp/compute/instances/n2-standard-8) (accessed 2026-08-26)
- [getdeploying.com — NVIDIA T4](https://getdeploying.com/gpus/nvidia-t4) (accessed 2026-08-26)
- [Google Cloud Storage pricing](https://cloud.google.com/storage/pricing) (accessed 2026-08-26, via search snippet)
- [Google Cloud Disk pricing](https://cloud.google.com/compute/disks-image-pricing) (accessed 2026-08-26, via search snippet)
- This project's own `gcloud compute accelerator-types list`/`gcloud compute machine-types list`/`gcloud compute regions describe` output (2026-08-26) — confirms T4 and L4 are both actually orderable in `us-central1-a` (subject to the quota caveat in §4).

## 1. Alternatives compared

| Option | Machine type | vCPU | RAM | GPU | VRAM | List price (USD/hr) |
|---|---|---|---|---|---|---|
| CPU-only | `n2-standard-8` | 8 | 32 GB | none | — | **$0.3885** |
| NVIDIA T4 | `n1-standard-4` + 1×T4 | 4 | 15 GB | T4 | 16 GB | **$0.19** (VM) + **$0.60** (GPU) = **$0.79** |
| NVIDIA L4 | `g2-standard-4` (bundled) | 4 | 16 GB | L4 | 24 GB | **≈$0.70–0.71** (bundled; exact us-central1 figure not independently confirmed, but us-east4/us-east1/us-west1 all cluster at $0.7045–$0.7068 — GCP's on-demand compute pricing is normally uniform across US regions in the same tier) |

Prices are **list/on-demand**, not sustained-use-discounted or committed-use. Spot/preemptible pricing for GPUs is typically 60–91% cheaper but the instance can be reclaimed at any time — noted as a future cost-reduction lever (§7), not used in the baseline estimate since an interrupted smoke test would be a worse outcome for a thesis deadline than a slightly higher guaranteed price.

## 2. Recommendation: NVIDIA L4 (`g2-standard-4`)

L4 is recommended over T4 for **two independent reasons**, not just cost:
1. **Cheaper**: ~$0.70/hr bundled vs. ~$0.79/hr for the T4 combination.
2. **VRAM fits BF16 without quantization**: Qwen2.5-7B-Instruct's weights are ~15.24 GB in BF16 (§3). On a 16 GB T4, that leaves under 1 GB for KV cache + CUDA context + activation buffers — **not enough headroom to run safely in BF16**; T4 would require 8-bit/4-bit quantization to fit, adding operational complexity (a different serving config, a quantized weight variant to source/verify separately, a real accuracy tradeoff to validate) that a thesis-scope deployment does not need. On a 24 GB L4, the same BF16 weights leave ~9 GB free — comfortably enough for KV cache at `max_model_len=8192` (§3) with room for several concurrent requests, no quantization needed.

CPU-only (`n2-standard-8`) is the cheapest option and was seriously considered given "minimizar costo" is the top priority, but is **not recommended as the primary choice**: CPU inference for a 7B model is typically an order of magnitude slower per token than GPU inference, which would make the chat feature's response latency (already a real product concern per Stage 81's timeout audit) impractical for an interactive UI, even though it would still work for a one-off scripted smoke test. It remains documented here as a valid fallback if the GPU quota blocker (§4) cannot be resolved before a deadline.

No A100/H100/multi-GPU configuration was priced or considered — explicitly out of scope per the stage brief, and unnecessary for a 7.6B-parameter model.

## 3. VRAM sizing (Qwen2.5-7B-Instruct, BF16)

- **Model weights**: 7.61B params × 2 bytes (BF16) ≈ **15.2 GB** — matches the Hugging Face API's actual file listing (15,242,807,270 bytes ≈ 15.24 GB across 4 safetensors shards).
- **KV cache per token**: `2 (K+V) × 4 kv_heads × 128 head_dim × 2 bytes (BF16) × 28 layers` ≈ 57,344 bytes ≈ **56 KB/token**.
- At `max_model_len = 8192`: ≈ 8192 × 56 KB ≈ **459 MB per full-length sequence** — trivial relative to a 24 GB card; several concurrent chat sessions fit easily even at the model's full context ceiling.
- At `max_model_len = 4096`: ≈ **230 MB per sequence** — the even more conservative option, chosen only if VRAM turns out tighter in practice than this estimate (e.g. due to vLLM's own activation/CUDA graph overhead, which this back-of-envelope calculation does not include).
- **Recommended `--max-model-len`: 8192** — this product's chat context (Stage 80 `StudyChatContextBuilder` output: one Study's disc-level/measurement JSON, optionally one longitudinal comparison, plus a Stage-81 bounded 8-turn history window) is small; there is no product reason to reserve memory for the model's full 131,072-token ceiling.
- **8-bit/4-bit quantization**: evaluated as a T4-enabling option (§2) but not needed for the recommended L4 path — not planned for the primary deployment.

## 4. Quota status (read-only, verified 2026-08-26)

- `us-central1` region quota: `NVIDIA_T4_GPUS = 1`, `NVIDIA_L4_GPUS = 1` (both allow exactly one GPU).
- **Project-wide quota `GPUS_ALL_REGIONS = 0`** — this is the binding constraint. Despite the per-region quota showing 1, **the project cannot actually provision any GPU anywhere until a quota increase is requested and approved** for `GPUS_ALL_REGIONS` (or the specific `NVIDIA_L4_GPUS`/committed equivalent). This is a common default for newer GCP billing accounts/projects. **A quota-increase request must be submitted via the Cloud Console (IAM & Admin → Quotas) before any real VM+GPU can be created** — this is a real blocker to execution, not a cost item, and is called out again in the deployment doc's prerequisites.

## 5. Cost estimates (L4 / `g2-standard-4`, recommended option)

| Duration | Cost (compute only, list price) |
|---|---|
| 1 hour smoke test | **$0.70** |
| 2 hour smoke test | **$1.40** |
| 4 hour smoke test | **$2.80** |
| 8 h/day, 30 days (240 h) | **$168.00/month** |
| 24/7, 30 days (720 h) | **$504.00/month** |

For comparison, the T4 path (`n1-standard-4` + T4) at the same durations: 1h ≈ $0.79, 2h ≈ $1.58, 4h ≈ $3.16, 8h/day ≈ $189.60/month, 24/7 ≈ $568.80/month. CPU-only (`n2-standard-8`): 1h ≈ $0.39, 8h/day ≈ $93.24/month, 24/7 ≈ $279.72/month (no GPU cost, but see §2's latency caveat).

**A single realistic Stage-82-style smoke session (model load + direct vLLM smoke + Spring→Qwen smoke + safety-prompt smoke + a short latency/concurrency check, estimated at 1–2 hours total) would cost approximately $0.70–$1.40 on L4 — well under the stage's ~$5 stop-condition threshold**, provided the VM is stopped promptly afterward (§8).

## 6. Storage costs

- **Model weights in Cloud Storage**: 15.24 GB × $0.020/GB-month (Standard class, us-central1) ≈ **$0.30/month** — negligible. The existing project bucket `gs://pfi-rm-lumbar-artifacts-2026-ef` (already in `us-central1`, Standard class) can be reused under a new top-level prefix (e.g. `conversational/qwen2.5-7b-instruct/`, kept clearly separate from the existing `models/` prefix used for imaging checkpoints) — **no new bucket needed**.
- **Persistent disk** (VM boot disk + local model cache, if weights are cached on-disk rather than re-pulled from GCS on every boot): an estimated 40–50 GB SSD/Balanced Persistent Disk (OS + Docker + vLLM + cached weights + headroom) × ~$0.17/GB-month (SSD PD, general US pricing — the official region-specific figure for us-central1 should be re-verified before real spend) ≈ **$6.80–$8.50/month if left attached continuously**. **Important**: stopping the VM (§8) stops compute billing but **does not stop persistent-disk billing** — a stopped VM with an attached disk still accrues this cost until the disk itself is deleted or detached.
- **Network egress**: chat responses are small JSON; the model weights themselves are pulled once (GCS→VM same-region traffic is free or near-free) rather than repeatedly, so network cost is expected to be negligible for this use case.

## 7. Possible cost reductions (not applied in the baseline estimate above)

- **Spot/preemptible L4 instance**: 60–91% cheaper per the general GCP spot-discount range, at the cost of possible mid-session reclamation — worth using once the deployment is proven stable and reproducible, riskier for a first real smoke test.
- **Stop-not-delete between sessions**: keeps the boot disk (and its small ongoing cost, §6) but avoids re-downloading/re-configuring the VM each time — cheaper than fully deleting and recreating for repeated short test sessions.
- **Committed-use discounts**: explicitly not recommended for a thesis-scope, intermittent-use deployment — committed-use only pays off for continuous, predictable usage, which this product does not have (§8's whole point is to keep the GPU off by default).

## 8. Shutdown strategy summary (detail in the Operations Runbook)

Stop (not necessarily delete) the VM immediately after each test session. A stopped Compute Engine VM with GPU attached stops the compute+GPU hourly charge but **the attached persistent disk keeps billing** (§6) — this is the "alerta ≠ enforcement" distinction the stage brief calls out: a billing budget alert **notifies**, it does not **stop** anything by itself. See `82_QWEN_OPERATIONS_RUNBOOK.md` for the exact stop/verify sequence and `82_QWEN_REAL_DEPLOYMENT.md` §"Budget controls" for the recommended budget-alert configuration.

## LIST PRICE vs. ESTIMATED ACTUAL USAGE

Every number in §1 and §5 is **Google's published on-demand list price** (or the closest verifiable cross-referenced equivalent, per the sourcing caveat above) — not a sustained-use discount, not a committed-use rate, not a free-tier credit. **Estimated actual usage** for this thesis project is expected to be intermittent (a handful of 1–2 hour smoke sessions, VM stopped between them, §8) — realistically in the **single-digit-to-low-double-digit-dollar total range** for the full Stage 82 real-deployment effort, not the $168–$504/month figures in §5, which describe sustained 8h/day or 24/7 operation that this project does not intend to run.
