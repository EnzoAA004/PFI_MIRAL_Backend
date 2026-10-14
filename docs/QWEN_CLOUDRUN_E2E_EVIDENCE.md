# Qwen Cloud Run E2E — phase evidence

Date: 2026-10-07 (UTC)
Project: `pfi-asplanatti-fabrello-v1` (`810538429027`)
Region: `us-central1`
Status: **paused before GPU service creation**

## Authentication and project preflight

- `gcloud config get-value account`: `enzoandreaasplanatti@gmail.com`.
- `gcloud config get-value project`: `pfi-asplanatti-fabrello-v1`.
- `gcloud projects describe pfi-asplanatti-fabrello-v1`: ACTIVE; project number `810538429027`.
- `gcloud services list --enabled --filter="name:cloudresourcemanager.googleapis.com"`: Cloud Resource Manager API enabled.
- The following APIs were confirmed enabled: Cloud Run, IAM, Cloud Storage, Artifact Registry, Cloud Build, Secret Manager, Compute Engine, and Service Usage.
- The active account already has `roles/owner` on this project. No IAM binding was changed during this continuation.

## Model loader

- Dedicated bucket `gs://pfi-qwen-models-810538429027` exists in `US-CENTRAL1`.
- Dedicated identities `pfi-qwen-model-uploader` and `pfi-llm-runtime` exist; bucket IAM grants the uploader `roles/storage.objectCreator` and runtime `roles/storage.objectViewer`. No `allUsers` binding was found.
- Cloud Run job `pfi-qwen-model-loader` was created with the pinned model revision `a09a35458c702b33eeacc393d103063234e8bc28` and a 2-hour timeout.
- First execution `pfi-qwen-model-loader-rdxbl` failed before uploading objects: Hugging Face returned HTTP 429 for `.gitattributes`.
- The job was updated with bounded HTTP retries for 429/5xx and integrity checks for already uploaded objects. Second execution `pfi-qwen-model-loader-6tmkf` made no download progress and was cancelled after approximately eight minutes to limit CPU/memory consumption.
- The model prefix `conversational/qwen2.5-7b-instruct/a09a35458c702b33eeacc393d103063234e8bc28/` is empty. No snapshot hashes or manifest have been produced.
- No job execution remains active. The job resource is retained for the next authorized attempt.

## GPU quota, region, and cost gate

- Cloud Run documentation currently lists L4 support in `us-central1`. It states that first-time L4 deployment can automatically receive 3 GPUs without zonal redundancy, subject to capacity.
- A read-only Service Usage query found the metric `run.googleapis.com/nvidia_l4_gpu_allocation_no_zonal_redundancy` and the `us-central1` dimension, but the API response contained no `effectiveLimit` or `defaultLimit` value for that dimension. Therefore the project's effective L4 quota is **not numerically verified**.
- The GPU service has **not** been created. Required intended scaling remains `min-instances=0`, `max-instances=1`, one L4 GPU, no zonal redundancy, 8 vCPU and 32 GiB.
- At current published `us-central1` rates, one active instance at that configuration is approximately `$0.0003947/second` (`$0.0001867` L4 + `8 × $0.000018` vCPU + `32 × $0.000002` GiB RAM), or about `$1.42/hour` while active. With minimum instances zero, Cloud Run instance charges stop when scaled to zero; active startup time is billable. This excludes storage and any other ancillary charges.

## Untouched certified services

No certified Final Freeze services or production services were changed during this continuation. No GPU service was created and no GPU quota was consumed.

## References

- [Cloud Run GPU support for services](https://docs.cloud.google.com/run/docs/configuring/services/gpu)
- [Cloud Run pricing](https://cloud.google.com/run/pricing)
- [Service Usage consumer quota metrics API](https://docs.cloud.google.com/service-usage/docs/reference/rest/v1beta1/services.consumerQuotaMetrics/list)
