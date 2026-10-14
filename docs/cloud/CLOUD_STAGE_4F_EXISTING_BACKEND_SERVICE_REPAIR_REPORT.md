# CLOUD_STAGE_4F_EXISTING_BACKEND_SERVICE_REPAIR Report

Date: 2026-09-22

## Objective

Repair the existing Cloud Run Backend service `pfi-backend-dev` without Java changes, without AI redeploy, without frontend redeploy, and with immediate rollback if any stability gate failed.

The previous stage proved that the diagnostic Backend image is healthy in a fresh Cloud Run service. This stage tested whether the existing service could be repaired by cleaning stale routing tags, creating a clean revision, and gradually moving real traffic through the normal service URL.

## Initial Snapshot

- Project: `pfi-asplanatti-fabrello-v1`
- Service: `pfi-backend-dev`
- URL: `https://pfi-backend-dev-7cxg46zwea-uc.a.run.app`
- Initial live revision: `pfi-backend-dev-00002-jwd`
- Initial traffic: `pfi-backend-dev-00002-jwd = 100%`
- Latest created revision before repair: `pfi-backend-dev-00019-xek`
- Latest ready revision before repair: `pfi-backend-dev-00019-xek`
- Service account: `pfi-backend-runtime@pfi-asplanatti-fabrello-v1.iam.gserviceaccount.com`
- Ingress: `all`
- Invoker IAM: `roles/run.invoker: allUsers`
- Execution environment: `gen2`
- CPU: `1`
- Memory: `512Mi`
- Concurrency: `80`
- Timeout: `300s`
- Max instances: `2`
- Min instances: default/zero
- CPU throttling: `false`
- Runtime env names:
  - `PFI_CORS_ALLOWED_ORIGINS`
  - `PFI_AUTH_DEMO_ENABLED`
  - `PFI_PERSISTENCE_MODE`
  - `PFI_STORAGE_PROVIDER`
  - `PFI_STORAGE_GCS_BUCKET`
  - `DATABASE_URL`
  - `PFI_AUTH_JWT_SECRET`
- Secret bindings:
  - `DATABASE_URL -> pfi-dev-database-url:latest`
  - `PFI_AUTH_JWT_SECRET -> pfi-dev-jwt-secret:latest`

No secret values were printed or stored in this report.

## Revision And Tag Inventory

The service had these notable routing entries at the start:

| Revision | Ready | Traffic | Tag |
| --- | --- | ---: | --- |
| `pfi-backend-dev-00002-jwd` | `True` | `100%` | |
| `pfi-backend-dev-00013-zam` | `True` | `0%` | `stage4-cloudsql-gcs` |
| `pfi-backend-dev-00016-fiw` | `True` | `0%` | `stage4b-pool` |
| `pfi-backend-dev-00019-xek` | `True` | `0%` | `stage4d-diagnostics` |

Additional old ready revisions existed with `0%` traffic and no tag:

- `pfi-backend-dev-00001-6qw`
- `pfi-backend-dev-00003-zew`
- `pfi-backend-dev-00004-tom`
- `pfi-backend-dev-00005-cuj`
- `pfi-backend-dev-00006-gol`
- `pfi-backend-dev-00007-jet`
- `pfi-backend-dev-00008-hil`
- `pfi-backend-dev-00009-pew`
- `pfi-backend-dev-00012-vuz`

No revisions were deleted.

## Routing Cleanup

Removed experimental traffic tags:

- `stage4-cloudsql-gcs`
- `stage4b-pool`
- `stage4d-diagnostics`

After tag cleanup:

- `pfi-backend-dev-00002-jwd = 100%`
- No traffic tags remained in the active traffic spec.

## Clean Revision

Created a clean revision in the existing service with no initial traffic:

- Revision: `pfi-backend-dev-stage4f-clean`
- Image: `us-central1-docker.pkg.dev/pfi-asplanatti-fabrello-v1/pfi-containers/backend:backend-cloud-diagnostics-0a4f084`
- Resolved image digest in revision: `sha256:39ae4cdc31693cf323e2485acd9fc3b0d27463a5bb3105cd945ccdd72368c068`
- Service account: `pfi-backend-runtime@pfi-asplanatti-fabrello-v1.iam.gserviceaccount.com`
- CPU: `1`
- Memory: `512Mi`
- Concurrency: `80`
- Timeout: `300s`
- Max instances: `2`
- Execution environment: `gen2`
- Port: `8080`
- CPU throttling: `false`
- Initial traffic: `0%`

Readiness:

- `Ready=True`
- `ContainerReady=True`
- `Active=False`, reason `Retired`, because the revision had no active traffic or tag before the split.

## Startup Logs

When the clean revision received traffic, Cloud Run started one instance:

- Instance start reason: `AUTOSCALING`
- Tomcat initialized on port `8080`
- Hikari pool started:
  - `pfi-postgres-pool - Starting...`
  - `pfi-postgres-pool - Start completed.`
- Application started in `20.879s`
- Startup probe succeeded after `4` attempts on `GET /api/system/health`
- Startup probe request returned `200`
- No application exceptions were observed in the clean revision logs.

After rollback, Cloud Run shut down the clean revision instance:

- `Shutting down user disabled instance`
- Hikari shutdown completed cleanly.

## 1% Traffic Gate

Traffic was changed to:

- `pfi-backend-dev-00002-jwd = 99%`
- `pfi-backend-dev-stage4f-clean = 1%`

Validation used the normal service URL only:

`https://pfi-backend-dev-7cxg46zwea-uc.a.run.app`

No tag URL or tag-routing header was used.

### 1% Request Batch

Requests: `250`

Request mix:

- `POST /api/auth/demo-doctor`
- `GET /api/system/health`
- `GET /api/system/runtime-diagnostics`
- Authenticated `GET /api/studies`
- Invalid-input check: `GET /api/studies/not-a-valid-id`

Observed statuses:

- `200`: `125`
- `401`: `124`
- `503`: `1`
- Timeouts/client transport failures: `0`

Latency across non-5xx responses:

- P50: `234ms`
- P95: `252ms`
- Max: `20628ms`

The single `503` occurred on `POST /api/auth/demo-doctor` after `21575ms`.

The `503` response had:

- No `X-PFI-Revision`
- No `X-Trace-Id`

The 401s were controlled authorization responses from the old live path during the mixed request batch; they were not treated as platform failures. The stability gate failed because any `503` during the 1% phase required rollback.

## 1% Logs

Cloud Run request logs captured during the 1% window showed request logs for the stable revision:

- `pfi-backend-dev-00002-jwd: 200 = 118`
- `pfi-backend-dev-00002-jwd: 401 = 117`

The clean revision logs showed startup/probe activity but no request logs for the failed `503` and no app-level exception. That makes the 503 a pre-app/pre-instrumentation serving failure from the active service routing path, not a traced Backend application error.

## Rollback

Rollback was executed immediately after the 1% gate failed:

- `pfi-backend-dev-00002-jwd = 100%`

Final traffic:

- `pfi-backend-dev-00002-jwd = 100%`

Post-rollback smoke:

- Requests: `60`
- `200`: `31`
- `401`: `29`
- 5xx/timeouts: `0`
- P50: `240ms`
- P95: `293ms`
- Max: `1437ms`

Rollback status: `SUCCESS`

## Later Gates

The following gates were not executed because the 1% stability gate failed:

- `10%`
- `50%`
- `100% cutover`
- Post-cutover validation
- Scale-to-zero/restart validation on the new revision

No further traffic escalation was attempted.

## Frontend Compatibility Smoke

No frontend redeploy was performed.

Basic HTTP smoke:

| URL | Result |
| --- | --- |
| `https://pfi-mvp-test-enzo-frontend.vercel.app` | `200`, HTML loaded |
| `https://pfi-frontend-dev-7cxg46zwea-uc.a.run.app` | `200`, HTML loaded |

The Backend service URL was unchanged, so frontend compatibility was preserved at the endpoint level. A full browser E2E was not executed because Backend repair did not pass the 1% gate.

## Cloud SQL, Hikari, And GCS

The clean revision startup confirmed:

- Hikari pool started successfully
- No Cloud SQL/Hikari startup error
- No storage startup error

However, the clean revision did not receive a successful DB-backed sampled request before rollback. Runtime diagnostics for the final live service returned `401` because the stable live revision `00002` does not expose the diagnostic endpoint in the same way as the new instrumented revision.

Therefore:

- Hikari metrics for the clean revision under real traffic: not measured
- Cloud SQL connection count under clean revision real traffic: not measured
- GCS write smoke: not executed

This is intentional: the 1% gate failed, so the experiment stopped instead of expanding scope.

## Result

Partial repair actions completed:

- Service snapshot captured
- Revision inventory captured
- Stale tags removed
- Clean revision created
- Clean revision startup succeeded
- 1% real traffic gate executed
- Rollback executed and verified

Repair did not complete:

- 1% traffic produced a `503`
- 10%, 50%, and 100% gates were not attempted
- Final live revision remains `pfi-backend-dev-00002-jwd`
- Old stable revision retained: `YES`

`EXISTING_SERVICE_ROUTING_REPAIRED = NO`

`READY_FOR_AI_REDEPLOY = NO`

## Recommended Next Step

Do not redeploy AI yet.

The existing service still shows pre-app/pre-instrumentation 503 behavior when real traffic is split to a new revision, even after stale tag cleanup and a clean revision creation. The safest next investigation is at the Cloud Run service serving/routing control-plane layer, not Java, Hikari, Cloud SQL, GCS, AI, or frontend.

Recommended options for the next stage:

1. Open a focused Cloud Run routing support investigation with the captured service snapshots and logs.
2. Create a replacement permanent Backend service only if acceptable operationally, since Stage 4E proved a fresh service can serve the same image cleanly.
3. Avoid further traffic escalation on `pfi-backend-dev` until the pre-app 503 behavior is understood.

