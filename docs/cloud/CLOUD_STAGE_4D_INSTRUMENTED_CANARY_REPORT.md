# CLOUD_STAGE_4D_INSTRUMENTED_CANARY_REPORT

Status: CLOUD_STAGE_4D_INSTRUMENTED_CANARY_DIAGNOSTIC_COMPLETE

Date: 2026-09-22

## 1. Project and account

| Item | Value |
|---|---|
| GCP project | `pfi-asplanatti-fabrello-v1` |
| Active gcloud account | `enzoandreaasplanatti@gmail.com` |
| Backend branch | `cloud-p0-durable-asset-persistence` |
| Source commit | `0a4f084fc0394de490069992b1ee22f1e30eb0c0` |

## 2. Live traffic state

Live traffic remained unchanged throughout this block.

| Revision | Percent | Tag |
|---|---:|---|
| `pfi-backend-dev-00002-jwd` | 100 | none |
| `pfi-backend-dev-00013-zam` | 0 | `stage4-cloudsql-gcs` |
| `pfi-backend-dev-00016-fiw` | 0 | `stage4b-pool` |
| `pfi-backend-dev-00019-xek` | 0 | `stage4d-diagnostics` |

Final live health check against the normal service URL returned 200 with app trace header.

## 3. Image

| Item | Value |
|---|---|
| Tag | `backend-cloud-diagnostics-0a4f084` |
| Artifact Registry digest | `sha256:4a6e404817e068bc568a583294ce9723d72b144e64f74d472e5884db0fa2e80d` |
| Cloud Run resolved image digest | `sha256:39ae4cdc31693cf323e2485acd9fc3b0d27463a5bb3105cd945ccdd72368c068` |
| Docker push size reported | `856` |

Note: Docker reported a manifest list digest during push; Cloud Run resolved the platform image manifest digest for the deployed revision.

## 4. Test evidence

Full suite was rerun before image build.

```text
Tests run: 1085, Failures: 0, Errors: 0, Skipped: 1
BUILD SUCCESS
Total time: 03:50 min
```

## 5. Revisions

| Role | Revision | Ready | ContainerHealthy |
|---|---|---|---|
| Stable live | `pfi-backend-dev-00002-jwd` | True | True |
| Previous canary | `pfi-backend-dev-00016-fiw` | True | True |
| Instrumented canary | `pfi-backend-dev-00019-xek` | True | True |

## 6. Config differences

| Setting | Stable `00002` | Instrumented `00019` | Classification |
|---|---|---|---|
| Image | old stable digest `75106a...` | diagnostics image digest `39ae4c...` | EXPECTED |
| Execution environment | unset/legacy in revision metadata | `gen2` | EXPECTED from Postgres/GCS canary line |
| Service account | `pfi-backend-runtime@...` | same | IRRELEVANT |
| CPU/RAM | `1` / `512Mi` | same | IRRELEVANT |
| Concurrency | `80` | same | IRRELEVANT |
| Timeout | `300s` | same | IRRELEVANT |
| Max instances | `2` | same | IRRELEVANT |
| Ingress | `all` | `all` | IRRELEVANT |
| Startup probe | `/api/system/health`, threshold `3` | `/api/system/health`, threshold `15` | EXPECTED canary tuning |
| Env names | CORS, demo, persistence mode | CORS, demo, persistence, storage, GCS bucket, DB secret, JWT secret | EXPECTED Postgres/GCS config |
| Secrets | none in stable | `DATABASE_URL`, `PFI_AUTH_JWT_SECRET` secret refs | EXPECTED |

Comparison of previous canary `00016` vs instrumented `00019`: same service account, gen2, CPU, memory, concurrency, timeout, max instances, ingress, startup probe, env names, secret refs, CPU throttling, session affinity. Only the image changed.

## 7. Routing evidence

| Mechanism | Target expected | Observed result | Interpretation |
|---|---|---|---|
| Base URL, no tag | Stable `00002` | 200, app `X-Trace-Id` | Healthy stable live path. |
| Base URL + `traffic-tag: stage4d-diagnostics` | Expected `00019` by previous playbook | 200 health without `X-PFI-Revision`; `/runtime-diagnostics` returned 401 from old auth surface | Header did not route to `00019`; it hit stable `00002`. |
| Base URL + `X-Cloud-Run-Tag: stage4d-diagnostics` | Expected `00019` | Same as above | Header did not route to `00019`. |
| Tag URL `stage4d-diagnostics---.../health` | `00019` | 503, no app trace/revision header | Pre-app platform failure. |
| Tag URL `stage4d-diagnostics---.../runtime-diagnostics` | `00019` | 503 after about 31-36s, no app trace/revision header | Pre-app platform failure. |
| Tag URL `stage4b-pool---.../health` | `00016` | client timeout at 45s | Tagged serving path failure also affects prior canary. |
| Tag URL `stage4-cloudsql-gcs---.../health` | `00013` | HTTP 500 generic Google HTML, no app trace | Tagged serving path failure also affects earlier canary. |

## 8. Runtime diagnostics

`/api/system/runtime-diagnostics` could not be reached on `00019` because the only real route to a 0% tagged revision, the tag-subdomain URL, failed before reaching the application.

The attempted header routes did not reach `00019`; they hit the stable revision and returned 401 for `/runtime-diagnostics`, which does not exist as a public path on `00002`.

## 9. Hikari and database evidence

Application logs for `00019` show Hikari starting successfully during cold starts:

```text
pfi-postgres-pool - Starting...
pfi-postgres-pool - Start completed.
```

No SQLException, Cloud SQL connector error, connection timeout, or Hikari pool exhaustion error was found in the sampled `00019` logs. Because runtime diagnostics was unreachable through the tag path, live Hikari MXBean counts could not be retrieved.

Cloud SQL instance read-only state:

| Item | Value |
|---|---|
| Instance | `pfi-postgres-dev` |
| Connection name | `pfi-asplanatti-fabrello-v1:us-central1:pfi-postgres-dev` |
| State | `RUNNABLE` |
| Database version | PostgreSQL 15 |
| Tier | `db-f1-micro` |
| Region/zone | `us-central1` / `us-central1-c` |

The installed gcloud SDK in this environment does not include `gcloud monitoring metrics` or `gcloud monitoring time-series`, so Cloud SQL active connection time series could not be retrieved from the CLI here.

## 10. Cloud Run logs and metrics from logs

Observed Cloud Run request/platform evidence:

| Revision | Path | Result | Platform/app distinction |
|---|---|---|---|
| `00002` | base `/api/system/health` | 200 | App reached; `X-Trace-Id` present. |
| `00019` | tag `/api/system/health` | 503 | Platform request log: malformed response or connection error; no app trace. |
| `00019` | tag `/api/system/runtime-diagnostics` | 503 | Platform request log: malformed response or connection error; no app trace. |
| `00013` | tag `/api/system/health` | 500 | Platform request log: no available instance; generic Google HTML. |
| `00016` | tag `/api/system/health` | timeout | No successful app response. |

`00019` startup probe itself succeeded repeatedly through `/api/system/health`, proving the container can boot and serve an internal Cloud Run probe. User-facing tag URL requests still failed before app headers.

## 11. 100-request canary

The planned 100-request canary was not executed because the canary routing gate failed first. Running 100 requests against a tag-subdomain that already returns 500/503/timeouts would add noise and instance churn without validating the revision.

The diagnostic gate result is therefore:

```text
CANARY_ROUTING_GATE=FAIL
100_REQUEST_CANARY=NOT_RUN
```

## 12. Root cause decision

```text
ROOT_CAUSE_IDENTIFIED=YES
```

Root cause identified for the false-positive canary and failed cutover decision: the previous “healthy canary” method using headers did not route requests to the tagged canary revision. It hit the stable revision instead. The actual tagged revision serving path is unhealthy before the request reaches application code.

This explains the contradiction:

1. Header “canary” checks appeared healthy because they were served by stable `00002`.
2. Real tag-subdomain checks failed with Google/frontend 500/503 and no app trace.
3. Moving traffic to the Postgres/GCS revision then exposed the revision/routing/startup path that had not actually been validated.

## 13. Remaining unknown

The exact lower-level Cloud Run reason why tagged revision user requests get aborted or reset while startup probes succeed is not fully isolated. Evidence points to Cloud Run tagged serving path / 0%-revision cold-start behavior for these revisions, not to application controller logic.

## 14. Top hypotheses after root cause

| Rank | Hypothesis | Evidence |
|---:|---|---|
| 1 | Tagged serving path for 0% revisions is not producing usable user traffic for this service/revision family | `00013`, `00016`, and `00019` tag URLs all fail, while stable base URL works. |
| 2 | Cold-start/scale-to-zero behavior on tagged 0% revisions aborts external requests before the app is ready | Logs show tag request 503, new instance start, startup probe success, app health logs, then shutdown/restart cycles. |
| 3 | Active 100% cutover likely failed because the Postgres/GCS revision was never truly validated through a real canary path | Header canary was proven to hit stable; tag path was already failing. |

## 15. Recommended next experiment

Recommended single next experiment: deploy the same diagnostic image to a separate temporary Cloud Run service cloned from `pfi-backend-dev` config, with no traffic split and no production route dependency, then hit that service's normal base URL.

Rationale: this isolates “tagged 0% revision routing” from “container/app/Postgres/GCS health”. Do not use tag-subdomain or header routing for the next proof. Do not cut over production traffic until the diagnostic service base URL proves health and runtime diagnostics.

## 16. Final state

| Item | Value |
|---|---|
| Stable live revision | `pfi-backend-dev-00002-jwd` |
| Live traffic | 100% stable |
| Instrumented revision | `pfi-backend-dev-00019-xek` |
| Instrumented traffic | 0% |
| AI changes | none |
| Frontend changes | none |
| Merge | none |

```text
ROOT_CAUSE_IDENTIFIED=YES
READY_FOR_NEW_CLOUD_CUTOVER_ATTEMPT=NO
READY_FOR_NEXT_ISOLATION_EXPERIMENT=YES
```