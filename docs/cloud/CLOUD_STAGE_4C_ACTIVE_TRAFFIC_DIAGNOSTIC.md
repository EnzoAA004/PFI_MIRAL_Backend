# CLOUD_STAGE_4C_ACTIVE_TRAFFIC_DIAGNOSTIC

Status: DIAGNOSTIC_INSTRUMENTATION_READY

Date: 2026-09-22

## 1. Executive summary

Stage 4B Backend connection pooling is implemented and validated. Commit `93273ad` preserves the pooling validation report, and the pooled revision passed local tests plus real Cloud SQL-backed canary traffic.

The remaining blocker is `ACTIVE_SERVING_PATH_FAILURE`: the same deployed Backend code path that succeeds through the safe `traffic-tag` canary path failed when promoted to normal active traffic. Source audit found no application-level Host-based routing, redirect, forwarded-header dependency, or health endpoint dependency that explains this difference.

Root cause is not identified yet because this environment cannot run `gcloud`, so live Cloud Run revision, traffic, logs, and metrics could not be verified. Runtime diagnostics have been added so the next deploy can prove which revision is serving, whether the app receives the request, and what Hikari pool state exists at request time.

## 2. Pooling status

`CLOUD_STAGE_4B_BACKEND_CONNECTION_POOLING` is `IMPLEMENTED_AND_VALIDATED`.

Evidence preserved in `docs/cloud/CLOUD_STAGE_4B_BACKEND_CONNECTION_POOLING_REPORT.md`:

| Evidence | Result |
|---|---|
| Backend full suite | 1171 tests, 0 failures, 0 errors, about 3.5 minutes |
| Real Cloud SQL local smoke | 25/25 DB-backed requests, 0 errors |
| Canary via `traffic-tag` | 100 DB-backed requests, 0 errors |
| Hikari defaults | `maximumPoolSize=4`, `minimumIdle=0`, `connectionTimeout=30000ms`, `idleTimeout=600000ms`, `maxLifetime=1700000ms` |
| Worst-case connections | 2 Cloud Run instances x 4 = 8 |

Report preservation commit:

```text
93273ad docs: record Backend Cloud SQL pooling validation
```

## 3. Known-good evidence

| Path | Evidence |
|---|---|
| Stable base health URL | `https://pfi-backend-dev-7cxg46zwea-uc.a.run.app/api/system/health` returned 200 on the stable revision. |
| Header canary health | Same base URL with `traffic-tag: stage4-cloudsql-gcs` returned 200. |
| Header canary mixed workload | 60/60 requests succeeded: 20 health requests and 40 authenticated `/api/studies` requests. |
| Header canary latency | p50 about 257ms, p95 about 294ms, max about 411ms. |
| App traceability on app-reached requests | Successful app responses include app-generated headers such as `X-Trace-Id`. |

## 4. Known-failing evidence

| Path | Evidence |
|---|---|
| Tag subdomain | `https://stage4-cloudsql-gcs---pfi-backend-dev-7cxg46zwea-uc.a.run.app/api/system/health` returned HTTP 500 after about 10s. |
| Tag subdomain response | Generic Google frontend HTML; no app `X-Trace-Id`. |
| 100% active traffic cutover | Normal service URL produced persistent 503/connection-reset/timeout symptoms after cutover, then was rolled back. |
| Rollback | Stable revision recovered normal base URL behavior. |

## 5. Request path matrix

| ID | Request path | Intended revision path | Result | App reached? | Notes |
|---|---|---|---|---|---|
| A | Base service URL + `traffic-tag: stage4-cloudsql-gcs` header | New pooled canary revision | 200 for health and authenticated DB-backed traffic | Yes | Best proof that the app and Cloud SQL pooling can work in Cloud Run. |
| B | Tag subdomain without `traffic-tag` header | New tagged revision through Cloud Run tag URL | 500 after about 10s | Not proven; likely no | Missing app trace header suggests pre-app or platform route failure. |
| C | Base service URL before/after rollback | Stable old revision | 200 | Yes | Documentation identifies stable active revision as memory-backed baseline; live verification still requires `gcloud`. |
| D | Base service URL after 100% cutover | New pooled active revision | Persistent 503/connection-reset/timeout symptoms | Unknown | Needs Cloud Run logs and revision config diff. |

## 6. Revision and routing comparison inventory

Live values could not be verified here because `gcloud` is not installed. Use this table as the required inventory shape for the next operator check.

| SETTING | OLD_STABLE_REVISION | NEW_CANARY_REVISION | NEW_ACTIVE_REVISION | DIFFERENCE | POSSIBLE_RELEVANCE |
|---|---|---|---|---|---|
| Revision name | `pfi-backend-dev-00002-jwd` per existing docs | Unknown live value | Unknown live value | Needs `gcloud run revisions list` | Confirms whether canary and active path targeted the same revision. |
| Traffic allocation | 100% after rollback | Tagged 0% traffic | 100% during failed cutover | Needs `gcloud run services describe` | Central symptom: tag-header path works, active path fails. |
| Traffic tag | None | `stage4-cloudsql-gcs` | None | Known operational difference | Header-tag routing and active routing may traverse different Cloud Run serving paths. |
| Image digest | Unknown live value | Unknown live value | Unknown live value | Needs revision describe | Rules out stale image or wrong revision. |
| Env vars | Unknown live value | Unknown live value | Unknown live value | Needs revision describe | Drift in persistence/storage/JWT/Cloud SQL settings could explain startup failure. |
| Secrets | Unknown live value | Unknown live value | Unknown live value | Needs revision describe | Secret mount/env mismatch could fail startup or auth paths. |
| Cloud SQL attachment | Unknown live value | Unknown live value | Unknown live value | Needs revision describe | Required for pooled Postgres mode. |
| Min/max instances | Unknown live value | Unknown live value | Unknown live value | Needs service/revision describe | Cold start, startup timeout, and connection budget relevance. |
| CPU/memory/concurrency | Unknown live value | Unknown live value | Unknown live value | Needs service/revision describe | Can alter startup and active-serving behavior. |
| Startup/liveness probes | Unknown live value | Unknown live value | Unknown live value | Needs revision describe | Probe failures can produce active traffic 503. |

## 7. Request pipeline audit

| Component | Host-sensitive? | Origin-sensitive? | Health dependency? | Finding |
|---|---:|---:|---:|---|
| `TraceIdFilter` | No | No | Adds `X-Trace-Id` and logs | If missing on a response, the request likely did not pass through the app filter chain. |
| `SecurityHeadersFilter` | No | No | Adds static security/cache headers | Cannot explain active-only 503. |
| `CorsResponseFilter` | No | Yes | CORS headers and preflight only | Non-OPTIONS health GET continues even with absent/disallowed Origin. |
| `AuthFilter` | No | No | `/api/system/health` and `/api/system/runtime-diagnostics` are public | URI-only public path check. |
| `SystemController.health` | No | No | Returns only `{"status":"ok"}` plus optional revision header | No DB, GCS, AI, JWT, or secrets dependency. |
| `ApiExceptionHandler` | No | No | Only if controller throws | App-level failures should return JSON and app traceability. |
| `SecurityStartupValidator` | No | No | Startup only | Can fail startup if security configuration is invalid. |
| `WebClientConfig` | No | No | AI outbound only | Not in health path. |

No productive code path was found using `getServerName`, `getRequestURL`, Host header routing, redirect logic, or forwarded-header decisions for health.

## 8. Health dependency graph

```text
GET /api/system/health
  -> TraceIdFilter
  -> SecurityHeadersFilter
  -> CorsResponseFilter
  -> AuthFilter public liveness allowlist
  -> SystemController.health(HttpServletResponse)
      -> optional RuntimeDiagnosticsService.revision()
      -> response header X-PFI-Revision when K_REVISION is present
      -> body {"status":"ok"}
```

Health does not open a database connection, call GCS, call the AI module, read JWT material, or inspect request Host/Origin. This makes health failure on active traffic especially useful: if `X-Trace-Id` and `X-PFI-Revision` are absent, the request probably failed before the controller.

## 9. Startup and database access map

| Phase | Component | Database behavior |
|---|---|---|
| Spring bean creation | `PfiDataSourceConfig` | Creates one `DataSource` bean only when `pfi.persistence.mode=postgres`. |
| Hikari creation | `PfiHikariDataSourceFactory` | Lazy pool init with `initializationFailTimeout=-1`; configured max pool size 4. |
| Repository constructors | Postgres repositories/storage | Run migrations with `dataSource.getConnection()` when migrations are enabled. |
| Health request | `SystemController.health` | No database access. |
| DB-backed request | Study/catalog/storage/auth services | Uses the shared Spring `DataSource` or optional provider depending on service. |

## 10. Hikari lifecycle audit

`PfiHikariDataSourceFactory` memoizes pools by `jdbcUrl|maxPoolSize` in a concurrent map. This fixed the previous test/JVM behavior where repeated repository construction could create many unclosed Hikari pools.

Production expected shape:

| Item | Expected |
|---|---|
| Spring `DataSource` beans in Postgres mode | 1 |
| Spring `DataSource` beans in memory mode | 0 |
| Productive `DriverManager` calls | 0 |
| Migration connection source | Shared `DataSource` |
| Max Cloud SQL connections at 2 instances | 8 |

The static memoization map intentionally keeps test pools alive until JVM exit; production uses the app-wide Spring bean.

## 11. Spring bean graph audit

| Bean family | Mode behavior |
|---|---|
| Memory repositories | Active in memory mode. |
| Postgres repositories | Active in Postgres mode and receive the shared `DataSource`. |
| `PostgresAuthStoreService` | Always present but uses `ObjectProvider<DataSource>` and only uses DB when configured. |
| `PostgresReviewStoreService` | Always present but uses optional `DataSource` provider. |
| `PostgresStudyCatalogService` | Always present but uses optional `DataSource` provider. |
| `SystemController` | Does not require DB for health. |
| `RuntimeDiagnosticsService` | Uses optional `DataSource` and exposes only non-secret operational state. |

## 12. Error handling observations

Application-level failures should generally include app-generated JSON and pass through `TraceIdFilter`. Known DB unavailability maps to a sanitized 503 through `ApiExceptionHandler`; generic runtime failures map to sanitized 500 responses.

The tag-subdomain failure returned generic Google frontend HTML without an app trace header. That evidence points away from normal controller/filter failure and toward pre-app routing, platform serving, revision startup, or proxy behavior. The active cutover 503 symptoms still require Cloud Run logs to separate pre-app platform failure from startup failure.

## 13. Diagnostics added

Code instrumentation added for the next revision:

| Diagnostic | Purpose |
|---|---|
| `GET /api/system/runtime-diagnostics` | Public, non-secret runtime snapshot for Cloud Run routing diagnostics. |
| `X-PFI-Revision` on `/api/system/health` | Confirms which Cloud Run revision reached the controller. |
| CORS exposed header `X-PFI-Revision` | Lets browser-based checks read the revision header. |
| Hikari pool metrics | Exposes max/min pool settings and live pool counts when available. |

The diagnostics intentionally omit JDBC URLs, bucket names, filesystem paths, JWT secrets, tokens, request headers, actor/user data, and credentials.

## 14. Local reproduction status

No local Host-header reproduction was executed in this pass. Source audit found no app code that branches on Host, forwarded host, server name, request URL, or redirect state for `/api/system/health`.

Suggested local checks before another cutover, after building the instrumented image:

```bash
curl -i http://localhost:8080/api/system/health
curl -i -H 'Host: pfi-backend-dev-7cxg46zwea-uc.a.run.app' http://localhost:8080/api/system/health
curl -i -H 'Host: stage4-cloudsql-gcs---pfi-backend-dev-7cxg46zwea-uc.a.run.app' http://localhost:8080/api/system/health
curl -i http://localhost:8080/api/system/runtime-diagnostics
```

## 15. Required read-only `gcloud` commands

This environment does not have `gcloud`; these commands must be run by an operator with Google Cloud SDK access. They are read-only.

```bash
gcloud config get-value project
```

```bash
gcloud run services describe pfi-backend-dev \
  --region=us-central1 \
  --project=pfi-asplanatti-fabrello-v1 \
  --format=json
```

```bash
gcloud run revisions list \
  --service=pfi-backend-dev \
  --region=us-central1 \
  --project=pfi-asplanatti-fabrello-v1 \
  --sort-by=~metadata.creationTimestamp \
  --format="table(metadata.name,status.conditions[?type='Ready'].status,metadata.creationTimestamp,metadata.labels.serving.knative.dev/configurationName)"
```

```bash
gcloud run revisions describe pfi-backend-dev-00002-jwd \
  --region=us-central1 \
  --project=pfi-asplanatti-fabrello-v1 \
  --format=json
```

```bash
gcloud run revisions describe <pooled-revision-name> \
  --region=us-central1 \
  --project=pfi-asplanatti-fabrello-v1 \
  --format=json
```

```bash
gcloud logging read 'resource.type="cloud_run_revision" AND resource.labels.service_name="pfi-backend-dev" AND severity>=ERROR' \
  --project=pfi-asplanatti-fabrello-v1 \
  --freshness=24h \
  --limit=200 \
  --format=json
```

```bash
gcloud logging read 'resource.type="cloud_run_revision" AND resource.labels.service_name="pfi-backend-dev" AND resource.labels.revision_name="<pooled-revision-name>"' \
  --project=pfi-asplanatti-fabrello-v1 \
  --freshness=24h \
  --limit=200 \
  --format=json
```

```bash
gcloud sql instances describe pfi-postgres-dev \
  --project=pfi-asplanatti-fabrello-v1 \
  --format=json
```

```bash
gcloud monitoring metrics list \
  --project=pfi-asplanatti-fabrello-v1 \
  --filter='metric.type:cloudsql.googleapis.com AND metric.type:connection'
```

## 16. Next deploy validation checklist

After deploying the instrumented revision at 0% traffic:

```bash
curl -i -H 'traffic-tag: stage4-cloudsql-gcs' https://pfi-backend-dev-7cxg46zwea-uc.a.run.app/api/system/health
curl -i -H 'traffic-tag: stage4-cloudsql-gcs' https://pfi-backend-dev-7cxg46zwea-uc.a.run.app/api/system/runtime-diagnostics
curl -i https://stage4-cloudsql-gcs---pfi-backend-dev-7cxg46zwea-uc.a.run.app/api/system/health
curl -i https://stage4-cloudsql-gcs---pfi-backend-dev-7cxg46zwea-uc.a.run.app/api/system/runtime-diagnostics
```

Expected decisive signals:

| Signal | Interpretation |
|---|---|
| `X-PFI-Revision` present on health | Request reached controller on that revision. |
| `X-Trace-Id` present | Request passed through app filters. |
| Runtime diagnostics returns JSON | App and public diagnostics route are healthy. |
| Runtime diagnostics absent but Google HTML/503 present | Failure likely before app controller. |
| Hikari metrics present | Confirms live pool shape under Cloud Run. |

Do not cut 100% active traffic until the header canary path and tag-subdomain path both produce app-reached diagnostic signals for the same revision.

## 17. Ranked hypotheses

| Rank | Hypothesis | Confidence | Evidence |
|---|---|---:|---|
| 1 | Cloud Run routing/service active path or platform proxy difference | High | Same revision path succeeds via `traffic-tag`; tag subdomain returns generic Google 500 without app trace. |
| 2 | Revision configuration drift between intended canary and active revision | Medium | Live revision describe could not be verified; env/secrets/Cloud SQL/probe drift remains possible. |
| 3 | Startup/readiness behavior under active serving differs from canary request path | Medium | Postgres startup migrations open DB connections; active traffic failure presented as 503/timeouts. |
| 4 | Application Host/Origin-sensitive logic | Low | Source audit found no Host/forwarded/redirect branch in health path; only CORS is Origin-sensitive and cannot explain GET health failure. |
| 5 | Hikari connection pooling regression | Low | Local and canary DB-backed traffic passed; pool max is bounded at 4 per instance. |
| 6 | Health endpoint dependency failure | Low | Health is minimal and does not touch DB/GCS/AI/secrets. |
| 7 | Cloud SQL connection exhaustion | Low to medium | Theoretical max is bounded, but live Cloud SQL metrics were unavailable here. |

## 18. Test evidence

Focused diagnostic tests passed with Java 21:

```text
Tests run: 43, Failures: 0, Errors: 0, Skipped: 0
```

Full suite passed with Java 21:

```text
Tests run: 1085, Failures: 0, Errors: 0, Skipped: 1
BUILD SUCCESS, Total time: 03:34 min
```

## 19. Blockers

| Blocker | Impact |
|---|---|
| `gcloud` unavailable in this environment | Cannot verify live service traffic, revision config, Cloud Run logs, or Cloud SQL metrics. |
| Active failure already rolled back | Cannot inspect live failed active path directly without a new controlled diagnostic deploy. |
| Instrumentation not deployed yet | Next Cloud Run attempt must build/deploy this code before it can produce decisive headers/diagnostics. |

## 20. Decision

```text
ROOT_CAUSE_IDENTIFIED=NO
READY_FOR_NEW_CLOUD_CUTOVER_ATTEMPT=NO
READY_FOR_INSTRUMENTED_CANARY_DEPLOY=YES
```

Rationale: the root cause is not proven. A direct 100% cutover should not be attempted yet. The next safe step is an instrumented 0% traffic canary deploy, then compare base-header, tag-subdomain, and active-routing signals before any traffic migration.
