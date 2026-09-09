# Rollback & Observability Design

Design only. No Alert Policy, no Monitoring dashboard, no rollback was executed or configured.

## Rollback mechanism (Cloud Run revision-based)

Cloud Run keeps every deployed revision addressable and supports traffic splitting between them
natively — no extra tooling needed:

1. Revision `N` is currently serving 100% of traffic.
2. CI/CD deploys revision `N+1` (same service, new image digest) with **0% traffic initially**, or
   deploys and immediately routes 100% traffic only after smoke tests pass (simpler for this
   project's current scale — canary/gradual rollout is a possible future refinement, not required
   now).
3. Automated smoke test hits `N+1`'s dedicated URL (Cloud Run gives every revision its own URL even
   before it receives tagged traffic) — checks health endpoint + one or two representative
   read-only API calls.
4. **If smoke tests fail**: traffic is never moved to `N+1`; `N` keeps serving, `N+1` is left
   deployed-but-untrafficked for inspection, and the pipeline fails the GitHub Actions run loudly.
5. **If a bad revision already received traffic and a problem is caught after the fact**: the
   rollback command is a single traffic-split update back to the last known-good revision —
   conceptually `gcloud run services update-traffic <service> --to-revisions=<good-revision>=100`
   — no rebuild, no redeploy, just a routing change. This is the whole point of keeping images
   immutable and SHA-tagged (see `CLOUD_CICD_DESIGN.md`): the "good" image is still sitting in
   Artifact Registry, untouched.

## How GitHub Actions detects failure

- Docker build step fails → PR/workflow fails, nothing deployed.
- Deploy step itself fails (Cloud Run rejects the revision) → workflow fails, previous revision
  keeps serving (Cloud Run's own default behavior — it does not cut traffic to a revision that
  failed to become ready).
- Smoke-test step (a small script/curl sequence run right after deploy) fails → workflow fails
  explicitly and does not proceed to the traffic-promotion step; DEV never gets the manual-approval
  prompt forwarded to DEMO.

## Minimum observability signals

| Signal | Why it matters here | Proposed source |
|---|---|---|
| HTTP 5xx rate (Backend, Frontend, AI) | Direct user-facing failure indicator | Cloud Run request logs → Cloud Logging metric |
| p50/p95 latency per service | Catches slow-AI-inference or DB-connection-pool regressions | Cloud Run built-in request latency metrics |
| Backend health check failures | Cloud SQL connectivity loss, startup validator failures | Actuator `/actuator/health` (once added) wired to Cloud Run startup/liveness probe |
| AI service timeout/5xx | Grad-CAM/inference errors, missing checkpoint (the EXT-03 subarticular gap found in the audit) | AI service structured logs → Cloud Logging |
| DB connection failures | Cloud SQL outage or connection-pool exhaustion | Cloud SQL insights + Backend logs |
| Container startup failures | Bad image, missing env var/secret at boot | Cloud Run deployment status + `SecurityStartupValidator` fail-closed logs |
| Failed analyses (product-level, not infra) | The actual thing patients/professionals care about | Backend application logs on analysis-pipeline exceptions |

## Proposed tooling

- **Cloud Logging**: structured logs from all three services (already logging via
  standard framework loggers; no code change required to start collecting them once deployed).
- **Cloud Monitoring**: built-in Cloud Run metrics (request count, latency, error rate, instance
  count, CPU/memory utilization) need no extra instrumentation to appear.
- **Alert Policies** (none created yet, all conceptual): 5xx rate above a threshold over 5 minutes;
  p95 latency above a threshold; zero successful requests for a service expected to be always-on
  (DEMO min-instance services); Cloud SQL connection count near its max.

## Not implemented by this document

No dashboard, no alert policy, no uptime check was created. This is the design CLOUD-07 implements.
