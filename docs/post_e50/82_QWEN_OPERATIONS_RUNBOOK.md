# Stage 82 — Qwen/vLLM Operations Runbook

Companion to `82_QWEN_REAL_DEPLOYMENT.md` (the one-time setup) — this document is for **day-to-day operation** once that setup exists. Nothing in this document has been executed; the three scripts below are prepared text, not yet created as files, pending Stage 82's actual execution authorization.

## start-llm.sh (prepared, not created)

```bash
#!/usr/bin/env bash
set -euo pipefail
ZONE="us-central1-a"
VM="pfi-llm-qwen"

echo "Starting $VM..."
gcloud compute instances start "$VM" --zone="$ZONE"

echo "Waiting for internal IP and vLLM health..."
INTERNAL_IP=$(gcloud compute instances describe "$VM" --zone="$ZONE" --format="value(networkInterfaces[0].networkIP)")
for i in $(seq 1 30); do
  if curl -sf "http://${INTERNAL_IP}:8000/health" > /dev/null 2>&1; then
    echo "vLLM healthy at http://${INTERNAL_IP}:8000"
    exit 0
  fi
  sleep 10
done
echo "vLLM did not become healthy within 5 minutes -- check logs (see 'How to check logs' below)."
exit 1
```

## status-llm.sh (prepared, not created)

```bash
#!/usr/bin/env bash
set -euo pipefail
ZONE="us-central1-a"
VM="pfi-llm-qwen"

STATUS=$(gcloud compute instances describe "$VM" --zone="$ZONE" --format="value(status)")
echo "VM status: $STATUS"
if [ "$STATUS" = "RUNNING" ]; then
  INTERNAL_IP=$(gcloud compute instances describe "$VM" --zone="$ZONE" --format="value(networkInterfaces[0].networkIP)")
  echo "Internal IP: $INTERNAL_IP"
  curl -sf "http://${INTERNAL_IP}:8000/health" && echo "vLLM: healthy" || echo "vLLM: NOT responding"
  curl -sf "http://${INTERNAL_IP}:8000/v1/models" || echo "model listing: NOT responding"
fi
```

## stop-llm.sh (prepared, not created)

```bash
#!/usr/bin/env bash
set -euo pipefail
ZONE="us-central1-a"
VM="pfi-llm-qwen"

echo "Stopping $VM..."
gcloud compute instances stop "$VM" --zone="$ZONE"

STATUS=$(gcloud compute instances describe "$VM" --zone="$ZONE" --format="value(status)")
echo "Confirmed status: $STATUS"
if [ "$STATUS" != "TERMINATED" ]; then
  echo "WARNING: expected TERMINATED, got $STATUS -- verify manually before assuming billing stopped."
fi
```

## How to turn it on

1. Confirm the GPU quota blocker is resolved (deployment doc, Prerequisites) — a stopped/failed quota request means `start-llm.sh` will fail at instance-creation time, not at start time, since the VM must already exist.
2. Run `start-llm.sh`. It polls `/health` for up to 5 minutes.
3. Run `status-llm.sh` to double-check both the VM status and the vLLM/model-listing endpoints independently.

## How to verify it's actually working

- `status-llm.sh` covers VM status + `/health` + `/v1/models` in one shot.
- A real chat round-trip: `curl -X POST http://<internal-ip>:8000/v1/chat/completions -d '{"model":"Qwen2.5-7B-Instruct","messages":[{"role":"user","content":"Respondé únicamente: OK"}]}'` — if this returns real generated content, the model is genuinely loaded and serving, not just the HTTP server up with an empty model.
- From Spring: `GET /api/system/diagnostics` (ADMIN token) — the `llm` field added in Stage 81 reports `enabled`/`status` (`ok`/`unavailable`) without a live-chat round trip, using the same cheap `GET /v1/models` probe as `checkAvailability()`.

## How to restart

```bash
gcloud compute instances reset pfi-llm-qwen --zone=us-central1-a
```
A `reset` is a hard reboot (not stop+start) — useful if the VM is up but vLLM itself is hung and a container restart alone doesn't recover it. Prefer restarting just the Docker container first (`docker restart pfi-vllm-qwen`) before a full VM reset, since a VM reset re-runs the boot sequence (§5 of the deployment doc) including the (idempotent, but not instant) `gsutil rsync` cache check.

## How to check logs

- **VM/boot logs**: `gcloud compute instances get-serial-port-output pfi-llm-qwen --zone=us-central1-a`
- **Container logs**: `docker logs pfi-vllm-qwen --tail 200` (SSH into the VM first via `gcloud compute ssh pfi-llm-qwen --zone=us-central1-a` — internal-IP-only VM, so this requires being on the same VPC or using Identity-Aware Proxy tunneling; documented here as a known operational detail, not solved in this planning stage).
- **Cloud Logging**: if the VM's logging agent is active (covered by the `roles/logging.logWriter` grant in the deployment doc §2), logs are also queryable via `gcloud logging read "resource.type=gce_instance AND resource.labels.instance_id=<id>"`.
- **What must NOT appear in any of these logs** (82-Y): full chat context, full question text if it may contain sensitive framing, or any patient-identifying data (none of which this product's context builder includes in the first place, per Stage 80's structural privacy guarantees — but vLLM's own default request logging should still be checked and, if it logs full prompt bodies by default, either disabled via a vLLM startup flag or piped through a log-scrubbing step before persistence).

## How to know if it's generating cost

- `status-llm.sh` reporting `RUNNING` means the VM (and its GPU) are billing.
- A budget alert (deployment doc, "Budget controls") notifies by email at 50/90/100% of the configured threshold — **this is a notification, not a stop**. The only way to be certain nothing is billing is `status-llm.sh` reporting `TERMINATED` (or `gcloud compute instances list` showing no running instances at all).
- Attached persistent disks and any reserved static IP keep billing (at a much smaller rate) even while the VM itself is `TERMINATED` — see the cost doc §6/§8. Full cost elimination requires deleting the instance (and, if truly done with the project, the disk and any reserved IP) — not covered by the routine stop/start cycle above, and not something to do without explicit authorization (NO borrado destructivo sin autorización).

## What to do if OOM

- vLLM logs (`docker logs`) will show a CUDA out-of-memory error, typically during model load or when a request's context + KV cache exceeds available VRAM.
- First response: confirm `--max-model-len` is still 8192 (not accidentally raised) and that no other process is also holding GPU memory (`nvidia-smi`).
- If OOM persists at model-load time even with default settings, the VRAM sizing estimate (cost doc §3) was optimistic for this specific vLLM version's overhead — the fallback is either a smaller `--max-model-len` (try 4096) or `--gpu-memory-utilization` tuned down from vLLM's default, both configuration changes, not a machine-type change, tried before concluding L4 is insufficient.

## What to do if model load fails

- Check `docker logs pfi-vllm-qwen` for the actual error before assuming a capacity problem — common real causes are: the local model path (`/mnt/models/qwen2.5-7b-instruct`) not actually populated (the `gsutil rsync` step in the deployment doc §5 failed or hasn't completed), a vLLM/transformers version mismatch with the Qwen2 architecture (re-check the pinned `vllm/vllm-openai:v0.27.1` tag is still compatible — re-verify against vLLM's own release notes, don't assume), or a driver/CUDA mismatch (the Deep Learning VM image in the deployment doc §4 is chosen specifically to avoid this class of problem).
- Re-run `gsutil rsync` manually and check the resulting local directory has all 4 `.safetensors` shards and `model.safetensors.index.json` before retrying the container.

## How to shut down properly at the end of a session

1. Run `stop-llm.sh`.
2. Confirm it printed `TERMINATED` (it self-checks; if it warns, investigate before walking away).
3. Note in this stage's report (or the next session's context) whether the disk/static IP were left in place intentionally (cheap, keeps the environment ready to restart quickly) or should be cleaned up (only with explicit authorization, per the project's standing "no destructive action without explicit ask" rule).
