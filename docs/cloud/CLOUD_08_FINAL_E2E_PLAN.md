# CLOUD-08 — Final Cloud E2E Test Plan (design only, not executed)

None of the steps below have been run against DEMO — DEMO doesn't exist yet (CLOUD-06 not applied).
This is the exact sequence a future session runs once CLOUD-05/06/07 are all applied and stable.

## Full E2E sequence

| Step | Action | Pass criterion |
|---|---|---|
| A | Frontend DEMO loads | HTTP 200 on `pfi-frontend-demo`'s root URL, app shell renders |
| B | Demo login works | Auth flow completes, session token issued |
| C | Backend auth works | Authenticated API call returns 200, unauthenticated returns 401 |
| D | Create synthetic study | POST creates a record, returns an ID |
| E | Persist entity | GET by ID returns the same data just created |
| F | Restart Backend | Force a new Cloud Run revision (trivial config touch) |
| G | Confirm persistence | GET by the same ID (step D) still returns the data — proves real DB persistence, not in-memory |
| H | Generate report artifact | Trigger whatever report-generation endpoint exists, confirm it returns a valid artifact |
| I | Validate Storage artifact (if enabled) | If the report is written to Cloud Storage, confirm the object exists and is readable by the Backend's own identity only |
| J | AI call — mocked or real, depending on approved state | If AI image was never pushed (current state), this step exercises the mock-AI path only; a real AI call is out of scope until AI's own push is separately authorized |
| K | Validate no direct Frontend→DB | Confirm the Frontend Cloud Run service's runtime SA has zero Cloud SQL IAM grant (already true today — `pfi-frontend-runtime-dev` has no roles at all) |
| L | Validate no direct Frontend→AI | Confirm Frontend never calls the AI service URL directly — only through the Backend proxy |
| M | Check logs | Confirm structured logs for the above requests appear in Cloud Logging with trace IDs, no secret value visible in any log line |
| N | Check alert readiness | Confirm the Backend 5xx / uptime-check alert policies exist and are in `ENABLED` state (not necessarily fired — just correctly configured) |
| O | Revision metadata captured | Record the exact revision name, image digest, and deploy timestamp serving traffic at the end of this E2E run, into an updated freeze manifest |

No cloud write occurs from steps A/B/C/D/E/F/G/H/I/J/K/L/M/N/O beyond the applications' own normal
runtime behavior (creating a study, generating a report) — this plan assumes CLOUD-05/06/07 are
already applied; it does not itself create infrastructure.

## Failure scenario plan (design only — no failure is provoked for real)

| Scenario | Expected detection | Expected block | Expected rollback | Expected logging |
|---|---|---|---|---|
| Backend unavailable | Uptime check fails (CLOUD-07) | Frontend shows a clear error state, not a silent hang | Traffic already at the last-known-good revision; no action needed unless the outage follows a new deploy, in which case `rollback-decision.sh` triggers `verify-cloudrun-revision.sh` | 5xx alert fires, log shows the connection failure with a trace ID |
| DB unavailable | `cloudsql-proxy` sidecar health probe fails | New revisions fail to become `Ready`; existing revision keeps serving if DB was previously reachable | If DB outage coincides with a bad new revision, roll back to the prior revision (DB-independent action) | Sidecar logs show connection refusal; Backend logs show the resulting 5xx |
| Bad secret (wrong value, not missing) | Backend fails auth/DB operations at runtime, not at startup (Secret Manager doesn't validate content) | Health probe may still pass (secret exists) but functional smoke fails | Roll back the secret version (`gcloud secrets versions add` a corrected value), not the Cloud Run revision | Sanitized error in logs (never the secret value itself — matches the existing `SensitiveDataLeakRegressionTest` guarantee) |
| CORS failure | Browser console shows a preflight rejection (exactly what CLOUD-04's real fix addressed) | Frontend UI shows a network-error state | Fix `PFI_CORS_ALLOWED_ORIGINS` (already solved once via the `google_cloud_run_v2_service` data-source pattern — same fix class applies to DEMO) | Backend logs show the CORS-rejected origin explicitly |
| Bad revision (fails to start) | Cloud Run's own readiness check keeps the bad revision at 0% traffic automatically | New revision never receives traffic — Cloud Run's default behavior, no manual gate needed | None needed — old revision was never displaced | Cloud Run deployment logs show the startup failure reason |
| Digest mismatch (DEMO ≠ DEV) | `verify-image-digest.sh` refuses to proceed before `apply` even runs | Promotion workflow fails at the verification step, before any Terraform action | N/A — nothing was deployed | Workflow log shows the expected vs. actual digest |
| Startup crash | Same as "bad revision" | Same | Same | Container stdout/stderr in Cloud Logging shows the stack trace |
| Failed migration (if a schema migration step is ever added) | Migration job/step exits non-zero | Deploy pipeline stops before flipping traffic | Database itself is unaffected if the migration is transactional; otherwise requires a manual, scenario-specific fix — no generic auto-rollback exists for a partially-applied schema change today | Migration tool's own log output |
| Failed DEMO smoke | `cloud-06-smoke.sh` exits non-zero | `cloud-06-promote-demo.yml` stops before declaring promotion complete | `cloud-06-rollback-demo.yml` tears down the just-created DEMO Cloud Run services | Workflow log shows which smoke check failed |

No scenario above was actually triggered — this is a design table for what future observation and
response should look like once the corresponding infrastructure (CLOUD-05/06/07) is real.
