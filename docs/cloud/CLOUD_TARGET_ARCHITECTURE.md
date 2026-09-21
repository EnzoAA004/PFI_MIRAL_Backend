# Cloud Target Architecture

Design only — nothing here is deployed. Builds directly on the pre-existing GCP state found in
`CLOUD_00_ARCHITECTURE_AUDIT.md` §0 (project `pfi-asplanatti-fabrello-v1` already has Artifact
Registry, one live Cloud Run service, one bucket, four service accounts).

## Overall flow

```
GitHub (push/PR)
  -> GitHub Actions (test, lint, typecheck, build, Docker build validation)
  -> [on push to a release branch] Workload Identity Federation (no JSON keys)
  -> Artifact Registry (pfi-containers, image tagged by git SHA)
  -> Deploy to Cloud Run DEV (per service)
  -> Automated smoke tests against DEV
  -> Manual approval gate
  -> Deploy the SAME image (no rebuild) to Cloud Run DEMO
  -> Cloud Monitoring / Logging / Alerting on both
```

No GKE, no Argo CD in this iteration — both documented only as a possible future evolution if the
number of services or the need for canary/blue-green traffic splitting outgrows what Cloud Run's
native revision/traffic-split features offer. See `ADR-001`.

## Per-component target

### Frontend — Cloud Run + nginx (confirmed choice)

Already Docker-native, already implements build-once/deploy-anywhere via `entrypoint.sh` +
`env.js` (see architecture audit). The one required change before deploy: template `nginx.conf`'s
`listen` directive from `$PORT` (currently hardcoded to `80`), the same pattern already used for
`BACKEND_URL`. No case found for switching to a static-hosting alternative (e.g. Cloud Storage +
CDN, or Firebase Hosting) — the project is already committed to a Docker-native, single-artifact
deployment story across all three services, and staying consistent avoids adding a second, unlike
CI/CD path just for one component.

### Backend — Cloud Run (confirmed choice)

Already binds `$PORT`, already stateless (no local filesystem writes), already fails closed
without Postgres. Required changes before deploy: (1) Secret Manager for JWT secret + DB
credentials (see `CLOUD_SECURITY_MODEL.md`), (2) add Actuator for a real health endpoint,
(3) point `DATABASE_URL` at Cloud SQL, (4) set real Frontend/AI/LLM URLs via env vars at deploy
time — all configuration changes, no code redesign.

### AI service — workload-classified, not one-size-fits-all

Not every AI workload should go to the same place:

| Workload | Compute need | Target |
|---|---|---|
| Product mock/demo inference (existing `models/final` classifiers, EXT-03 Grad-CAM) | CPU-only, already proven to run without CUDA | Cloud Run (same as Backend/Frontend) |
| Real segmentation/classification at higher throughput, if it ever needs sustained CPU load beyond what Cloud Run's per-request billing suits | CPU-heavy | Evaluate Compute Engine (steady-state pricing may beat Cloud Run at high sustained utilization) — not needed today |
| Qwen 2.5 7B (EXT-06) | GPU-required | Stays blocked — no GPU created without explicit separate cost authorization; would need Compute Engine or Cloud Run with GPU support if ever authorized |

The pre-existing `pfi-ai-module` Cloud Run service is a candidate to become (or be replaced by) the
product AI service target — needs a content/purpose check before reuse (see architecture audit §0).

### Database — Cloud SQL PostgreSQL (primary candidate) vs. dev alternative

For DEV, a lower-cost path exists (`db-f1-micro` shared-core Cloud SQL, or even a temporary
container-based Postgres for pure integration testing that never needs to persist). For DEMO and
any real target, Cloud SQL is the right candidate: managed backups, IAM-integrated auth, no
operational burden. The explicit tradeoff to keep visible: **Cloud SQL is the only always-on cost
in this whole stack** (see `CLOUD_COST_MODEL.md`) — every other component can scale to zero.

## Security posture (summary — full detail in `CLOUD_SECURITY_MODEL.md`)

- GitHub Actions authenticates via Workload Identity Federation, never a downloaded JSON key.
- Runtime least privilege: Frontend has no DB/Secret Manager/AI access; Backend has DB + Secret
  Manager + internal AI endpoint + authorized Storage; AI service has the minimum needed to read
  its own model artifacts and nothing else.

## Not implemented by this document

No Terraform resource, no `gcloud` mutating command, no new Cloud Run revision. This is a design
artifact for CLOUD-01 onward to implement, each gated by its own authorization checkpoint (see
`CLOUD_ROADMAP.md`).
