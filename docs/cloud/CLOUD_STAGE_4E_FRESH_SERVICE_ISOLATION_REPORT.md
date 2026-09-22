# CLOUD_STAGE_4E_FRESH_SERVICE_ISOLATION Report

Date: 2026-09-22

## Objective

Isolate whether the Backend Cloud Run failure observed on `pfi-backend-dev` revision/tag serving is caused by the Backend image/runtime/configuration or by the existing Cloud Run service routing/tagging/traffic layer.

The experiment created one temporary Cloud Run service, validated it with the same Backend diagnostic image and equivalent runtime settings, and then deleted the temporary service. No production traffic cutover was performed.

## Safety Baseline

- Project: `pfi-asplanatti-fabrello-v1`
- Active account used during the experiment: `enzoandreaasplanatti@gmail.com`
- Live service: `pfi-backend-dev`
- Live traffic before and after the experiment:
  - `pfi-backend-dev-00002-jwd`: `100%`
  - `pfi-backend-dev-00013-zam`: `0%`, tag `stage4-cloudsql-gcs`
  - `pfi-backend-dev-00016-fiw`: `0%`, tag `stage4b-pool`
  - `pfi-backend-dev-00019-xek`: `0%`, tag `stage4d-diagnostics`

The live Backend service remained untouched. The temporary service was isolated as `pfi-backend-stage4e-isolation`.

## Source Revision

The source diagnostic revision from the existing service was:

- Revision: `pfi-backend-dev-00019-xek`
- Image resolved by Cloud Run revision: `us-central1-docker.pkg.dev/pfi-asplanatti-fabrello-v1/pfi-containers/backend@sha256:39ae4cdc31693cf323e2485acd9fc3b0d27463a5bb3105cd945ccdd72368c068`
- Diagnostic image tag used for the fresh service: `us-central1-docker.pkg.dev/pfi-asplanatti-fabrello-v1/pfi-containers/backend:backend-cloud-diagnostics-0a4f084`
- Artifact Registry digest captured for the tag: `sha256:4a6e404817e068bc568a583294ce9723d72b144e64f74d472e5884db0fa2e80d`
- Service account: `pfi-backend-runtime@pfi-asplanatti-fabrello-v1.iam.gserviceaccount.com`
- CPU: `1`
- Memory: `512Mi`
- Concurrency: `80`
- Timeout: `300s`
- Execution environment: `gen2`
- Ingress: `all`
- Max instances: `2`
- Secrets:
  - `DATABASE_URL` from `pfi-dev-database-url`
  - `PFI_AUTH_JWT_SECRET` from `pfi-dev-jwt-secret`
- Startup probe: `GET /api/system/health` on port `8080`, `failureThreshold=15`, `periodSeconds=10`, `timeoutSeconds=5`

## Fresh Service Configuration

Temporary service:

- Service: `pfi-backend-stage4e-isolation`
- Region: `us-central1`
- URL while active: `https://pfi-backend-stage4e-isolation-7cxg46zwea-uc.a.run.app`
- Ready revision: `pfi-backend-stage4e-isolation-00002-hxd`
- Traffic: `100%` to `pfi-backend-stage4e-isolation-00002-hxd`
- Image: `us-central1-docker.pkg.dev/pfi-asplanatti-fabrello-v1/pfi-containers/backend:backend-cloud-diagnostics-0a4f084`
- Service account: `pfi-backend-runtime@pfi-asplanatti-fabrello-v1.iam.gserviceaccount.com`
- CPU: `1`
- Memory: `512Mi`
- Concurrency: `80`
- Timeout: `300s`
- Execution environment: `gen2`
- Ingress: `all`
- Min instances: default/zero
- Max instances: `1`

The only intentional runtime difference from the source revision was `max-instances=1` instead of `2`, to keep the isolation test low cost.

## Initial Deploy Note

The first temporary revision, `pfi-backend-stage4e-isolation-00001-7ms`, failed startup because the local PowerShell/gcloud command malformed the startup probe path into:

`/api/system/health httpGet.port=8080 periodSeconds=10 timeoutSeconds=5 failureThreshold=15`

Cloud Run then probed that incorrect path and received `401 Unauthorized`. This was a command construction issue, not an application/runtime failure. The application itself started, Tomcat bound to port `8080`, and Hikari started before the malformed probe rejected the revision.

The corrected redeploy produced `pfi-backend-stage4e-isolation-00002-hxd`, which became ready and served traffic successfully.

## Validation Results

### First Request

`GET /api/system/health`

- Status: `200`
- Latency: `565ms`
- Trace header present: `X-Trace-Id: trace-0c7d8cbe-191d-4706-9625-448130deba19`
- Revision header present: `X-PFI-Revision: pfi-backend-stage4e-isolation-00002-hxd`
- Body: `{"status":"ok"}`

### Runtime Diagnostics

`GET /api/system/runtime-diagnostics`

- Status: `200`
- Latency: `244ms`
- Trace header present: `X-Trace-Id: trace-10f951f8-8ab1-431d-91bc-cdd4edca847e`
- Cloud Run detected service: `pfi-backend-stage4e-isolation`
- Cloud Run detected revision: `pfi-backend-stage4e-isolation-00002-hxd`
- Persistence mode: `postgres`
- Postgres enabled: `true`
- Storage provider: `gcs`
- GCS configured: `true`
- DataSource bean present: `true`
- Pool type: `HikariDataSource`
- Maximum pool size: `4`
- Minimum idle: `0`
- Pool metrics available: `true`
- Connections at first diagnostics:
  - total: `1`
  - active: `0`
  - idle: `1`
  - threads awaiting connection: `0`

### 100 Request Mixed Smoke

The fresh service received 100 real Backend requests:

- `POST /api/auth/demo-doctor`: `1`
- `GET /api/system/health`: `39`
- `GET /api/system/runtime-diagnostics`: `30`
- Authenticated `GET /api/studies`: `30`

Result:

- Total requests: `100`
- Status summary: `200=100`
- Errors/timeouts: `0`
- P50 latency: `235ms`
- P95 latency: `256ms`
- Max latency: `535ms`

### Post Load Runtime Diagnostics

After the 100 request smoke:

- Status: `ok`
- Service: `pfi-backend-stage4e-isolation`
- Revision: `pfi-backend-stage4e-isolation-00002-hxd`
- Persistence: `postgres`
- Storage: `gcs`
- Hikari:
  - maximum pool size: `4`
  - minimum idle: `0`
  - total connections: `1`
  - active connections: `0`
  - idle connections: `1`
  - threads awaiting connection: `0`

The Backend pool stayed bounded and did not accumulate connections during the isolation smoke.

## Logs

Fresh service logs captured for the full experiment contained the expected failed first revision and the successful corrected revision.

For corrected revision `pfi-backend-stage4e-isolation-00002-hxd`:

- Log entries: `231`
- Request logs: `102`
- Request status summary: `200=102`
- Error, critical, or 5xx entries: `0`

For malformed-probe revision `pfi-backend-stage4e-isolation-00001-7ms`:

- Error or 5xx entries: `2`
- Cause: startup probe path malformed by the local deploy command, excluded from the Backend decision.

No platform 5xx, crash-loop, OOM, SIGTERM, application exception, or Hikari/Cloud SQL error was observed on the successful revision during the smoke.

## Cloud SQL

The fresh service successfully executed real DB-backed requests:

- Demo doctor auth request returned `200`
- Authenticated `GET /api/studies` returned `200` for 30/30 requests
- Runtime diagnostics showed the app using `postgres`
- Hikari pool metrics remained bounded at `totalConnections=1`, `activeConnections=0`, `idleConnections=1`, `threadsAwaitingConnection=0`

Cloud SQL global connection time-series were not required to make the isolation decision. The application-level pool telemetry and DB-backed request success prove the fresh service could connect and serve through the shared pooled DataSource without degradation.

## GCS Smoke

Direct non-AI Backend GCS write smoke was not run in this step.

Reason: the Backend routes that durably persist study input objects are behind AI upload/reprocess flows. Running them would exercise AI-adjacent behavior and broaden the isolation experiment beyond the requested Backend service/routing question.

The fresh service did confirm:

- `PFI_STORAGE_PROVIDER=gcs`
- GCS configured: `true`
- No storage configuration error during application startup

## Comparison With Existing Service

Known from the previous diagnostic stage:

- `pfi-backend-dev-00019-xek` tag URL failed before the application with `503`
- The failure path produced no Backend trace/revision headers
- Header-based canary checks had been false positives because `traffic-tag` and `X-Cloud-Run-Tag` did not route to the tagged revision; they reached stable live revision `pfi-backend-dev-00002-jwd`

This 4E isolation run shows that the same Backend diagnostic image and copied runtime configuration are healthy when deployed into a fresh Cloud Run service.

## Decision Matrix

| Hypothesis | Evidence | Result |
| --- | --- | --- |
| Backend image/runtime is broken | Fresh service using the diagnostic image served 100/100 mixed requests with all 200s | Rejected |
| Cloud SQL connectivity/pooling is broken | Real DB-backed requests passed and Hikari remained bounded | Rejected |
| GCS configuration prevents startup | Fresh service startup and diagnostics passed with GCS configured | Rejected for startup/config |
| Existing service routing/tagging/traffic layer is broken | Existing tagged revision path fails pre-app, while fresh service path for same diagnostic image is healthy | Confirmed |

Result: `SERVICE_ROUTING_LAYER_CONFIRMED`

Root failure layer: `EXISTING_SERVICE_ROUTING_TAGGING_TRAFFIC_CONFIGURATION`

## Cleanup

Temporary service deletion was executed:

`gcloud run services delete pfi-backend-stage4e-isolation --region=us-central1 --project=pfi-asplanatti-fabrello-v1 --quiet`

Deletion completed successfully:

`Deleted service [pfi-backend-stage4e-isolation].`

Verification immediately afterward:

`gcloud run services describe pfi-backend-stage4e-isolation ...`

Result:

`Cannot find service [pfi-backend-stage4e-isolation]`

No artifact image, Cloud SQL instance, bucket, secret, or live service resource was deleted.

## Cost Envelope

The experiment used:

- One temporary Cloud Run service
- One corrected serving revision
- `min-instances=0`
- `max-instances=1`
- Approximately 100 mixed validation requests plus diagnostics

The temporary service was deleted after evidence collection, so no ongoing Cloud Run cost remains from the isolation service.

## Recommended Next Step

Do not redeploy AI/frontend as the next action.

The next safe Backend step is to repair or replace the `pfi-backend-dev` Cloud Run service routing/tagging/traffic configuration, using the fresh-service result as proof that the Backend diagnostic image itself is healthy.

Recommended gate before any future AI redeploy:

- `READY_FOR_EXISTING_SERVICE_REPAIR = YES`
- `READY_FOR_AI_REDEPLOY = NO`

