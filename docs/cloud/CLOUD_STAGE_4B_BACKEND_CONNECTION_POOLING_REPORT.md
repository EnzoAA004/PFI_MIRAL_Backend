# CLOUD_STAGE_4B_BACKEND_CONNECTION_POOLING_REPORT

Date: 2026-09-22
Branch: `cloud-p0-durable-asset-persistence`
Commit: `bb859c4` (`fix: pool Cloud SQL database connections`)

## Status

`READY_FOR_AI_REDEPLOY = NO`

Backend pooling code is implemented, committed, pushed, locally tested, image-built, deployed as a canary revision, and healthy through the proven `traffic-tag: stage4-cloudsql-gcs` header path.

It is not safe to cut over real traffic yet. The separate tag-subdomain serving path still fails, and the previous 100% base-URL cutover failed persistently and was rolled back. Real traffic remains on the stable memory-mode revision.

## 1. Pooling Test Results

Focused pooling test:

- `PfiHikariDataSourceFactoryTest`: 6 tests, 0 failures, 0 errors, 0 skipped, 3.495s.

Full Backend suite from `target/surefire-reports`:

- XML report files: 164
- Tests: 1171
- Failures: 0
- Errors: 0
- Skipped: 1
- Aggregate surefire time: 210.533s
- Exit code reported by previous run: 0

The two long tests observed in the suite are expected failure-path tests governed by Hikari `connectionTimeout=30000ms`, not hangs.

## 2. Physical Connection Reuse Evidence

Before the fix, repeated repository construction in one test class created new Hikari pools and left live sockets accumulated across the JVM test run. After memoizing pools by `(jdbcUrl, maximumPoolSize)`, the same problematic test file dropped from roughly 1234s to 3.7s in the prior run.

Local canary smoke after deploy:

- 60 requests through `traffic-tag: stage4-cloudsql-gcs`
- 20 `GET /api/system/health`
- 40 authenticated `GET /api/studies`
- 60/60 returned 200
- 5xx/timeouts: 0
- p50: 0.256629s
- p95: 0.293679s
- max: 0.41125s

## 3. Final Hikari Settings

Defined in `PfiHikariDataSourceFactory` code defaults:

- `maximumPoolSize=4`
- `minimumIdle=0`
- `connectionTimeout=30000ms`
- `idleTimeout=600000ms`
- `maxLifetime=1700000ms`
- `initializationFailTimeout=-1`

Configuration source: code default. No env var or `application.properties` override is currently wired for these values.

## 4. Max Theoretical DB Connections

Cloud Run max instances: 2

`2 instances x maximumPoolSize 4 = 8` theoretical Backend DB connections.

This is deliberately small for the current `db-f1-micro` Cloud SQL DEV instance.

## 5. Remaining DriverManager Usages

Product code audit:

- Productive `DriverManager.getConnection(...)`: 0
- Remaining mention: one explanatory comment in `PfiHikariDataSourceFactory`
- Test-only direct `DriverManager` calls remain in integration setup/fixture code and are not product runtime paths.

## 6. Repository / Service DataSource Inventory

All production Postgres runtime paths now use `DataSource`:

- `PostgresStudyRepository`: DataSource
- `PostgresPatientRepository`: DataSource
- `PostgresDurableObjectAssetRepository`: DataSource
- `PostgresRunAssetContentStorage`: DataSource
- `PostgresReviewStoreService`: DataSource
- `PostgresStudyCatalogService`: DataSource
- `PostgresAuthStoreService`: DataSource
- Spring app-wide bean: `PfiDataSourceConfig`

## 7. Migration Runner Behavior

`SqlMigrationRunner` accepts an existing `Connection`; it does not create its own connection or call `DriverManager`.

Migration application is invoked from the owning Postgres components using `dataSource.getConnection()`, so migration startup uses the shared pooled DataSource path rather than one Cloud SQL Connector handshake per migration file.

## 8. Local Connector Smoke

Prior run evidence reported:

- 25 real DB-backed requests against Cloud SQL
- All 200 OK
- Stable latency around 180ms
- No degradation observed

## 9. Image / Deploy Evidence

Commit pushed:

- `bb859c4`

Image tag reported by prior run:

- `backend-cloud-sql-gcs-pool-bb859c4`

Canary revision:

- new pooled Backend revision deployed at 0% traffic
- tagged for `stage4-cloudsql-gcs`
- baseline settings preserved: gen2, 1 vCPU, 512Mi, min=0, max=2, concurrency=80

Digest and image size were not recoverable from this session because `gcloud` is not installed or available in `PATH`.

## 10. Canary Result

Header-based canary path:

```text
GET /api/system/health with traffic-tag: stage4-cloudsql-gcs -> 200 {"status":"ok"}
POST /api/auth/demo-doctor with traffic-tag: stage4-cloudsql-gcs -> 200 token issued
60 mixed requests -> 60 200, 0 5xx, 0 timeouts
```

Separate tag-subdomain path:

```text
GET https://stage4-cloudsql-gcs---pfi-backend-dev-7cxg46zwea-uc.a.run.app/api/system/health
-> HTTP 500 after about 10s
```

Stable base URL:

```text
GET https://pfi-backend-dev-7cxg46zwea-uc.a.run.app/api/system/health
-> 200 {"status":"ok"}
```

## 11. Cutover / Rollback Status

Previous 100% cutover attempt failed persistently over multiple attempts and was rolled back immediately.

Current safe state from the prior deployment document:

- `pfi-backend-dev` live traffic remains 100% on stable revision `pfi-backend-dev-00002-jwd`
- pooled canary revision remains at 0% real traffic
- rollback status: completed

No new cutover was attempted in this continuation because the tag-subdomain path still fails.

## 12. Hikari / Cloud SQL Metrics

Not available from this continuation session because Google Cloud SDK is not installed or discoverable:

- `gcloud` not in `PATH`
- no local `gcloud.cmd`, `gcloud.ps1`, or `gcloud.exe` found in the searched standard locations

Therefore Cloud Run revision metadata, Cloud SQL active connections, CPU, memory, and connection-error metrics were not re-read here.

## 13. GCS + Study E2E

Not executed.

Reason: real traffic cutover is not stable yet. Per the stage gate, GCS + Study E2E must run only after the pooled Backend is safely serving real traffic.

## 14. Blockers

Primary blocker:

- pooled revision is healthy through the `traffic-tag` header route, but the separate tag-subdomain route still returns 500 and the prior 100% base-URL cutover failed.

Operational blocker in this session:

- `gcloud` is unavailable, so metrics and traffic changes cannot be performed from this shell.

## 15. Next Safe Step

Do not redeploy AI, do not touch Frontend, and do not merge.

Next operator step should be Cloud Run serving-path diagnosis with Cloud SDK available:

1. Confirm current traffic split and tagged revision.
2. Read logs for the failed tag-subdomain request around `2026-09-22T20:20:41Z`, trace `f9e43959f94be4eb76e66e838a5e5f3f`.
3. Compare request headers/environment seen through `traffic-tag` header versus tag-subdomain route.
4. Only if the serving-path failure is resolved, repeat 50+ canary requests and then attempt a guarded traffic cutover with immediate rollback ready.
