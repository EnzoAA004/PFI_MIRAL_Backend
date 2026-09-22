# Cloud Deployment Stage 1 — Real Execution Plan (read-only prep, no writes)

Everything in this document is a plan against the **actual, live state** of
`pfi-asplanatti-fabrello-v1`, gathered via read-only `gcloud`/`terraform plan`-adjacent
inspection on 2026-09-22. No resource was created, modified or deleted while gathering
it. Where a command below would create/modify/delete a billed resource, it is marked
`[NOT EXECUTED -- requires explicit authorization]` and must not be run without that
authorization arriving in a later, separate instruction.

## Backend service status (updated 2026-09-22, after Stage 4I)

| Service | Role | Status |
|---|---|---|
| `pfi-backend-dev` | **LEGACY / ROLLBACK** | Still serving 100% of Backend traffic (`pfi-backend-dev-00002-jwd`), untouched throughout Stages 4G/4H/4I. Its percentage-split traffic mechanism was found unreliable (Stages 4G/4H) — do not use `update-traffic` percentage splits on this service again; keep it only as the safety rollback target. |
| `pfi-backend-v2-dev` | **GREEN CANDIDATE** | New, single-revision-only Cloud Run service (blue/green replacement, not a revision split). Passed 1000-request concurrent warm load (0 errors) and 3/3 natural scale-to-zero cold-start cycles (all 200, ~20.6s cold / ~0.25s warm) — see `CLOUD_STAGE_4I_BACKEND_GREEN_SERVICE_REPORT.md`. **Not yet production-ready**: Frontend still points at the legacy URL, AI is not wired, and full DICOM E2E has not been exercised against it. |

Do not call `pfi-backend-v2-dev` "production-ready" until the Frontend
switch (Stage 9 of the roadmap below) and AI wiring (Stages 5-8) are
also validated.

**Roadmap from here** (per user direction, 2026-09-22):
Stage 5 (AI durable redeploy) → Stage 6 (Backend v2 → private AI) →
Stage 7 (full DICOM E2E) → Stage 8 (persistence/restart evidence) →
Stage 9 (Frontend → Backend v2) → Stage 10 (real cost measurement).

## 0. Stage 2 status (2026-09-22, executed)

`CLOUD_DEPLOYMENT_STAGE_2_LOW_COST_FOUNDATION` ran the subset of §3–§6 below that was
explicitly authorized. Everything else in this document (Cloud SQL instance, Backend/AI
redeploy, AI image push) remains `[NOT EXECUTED]`.

**APIs enabled** (previously disabled, confirmed via `gcloud services list --enabled`
before/after):
- `secretmanager.googleapis.com` — enabled
- `sqladmin.googleapis.com` — enabled

**Cloud SQL tier check** (`gcloud sql tiers list`, read-only, no instance created):
`db-f1-micro` is available in `us-central1` (614.4 MiB RAM, shared-core). The tiers API
does not expose per-engine/per-edition compatibility, so `POSTGRES_15` + `ENTERPRISE`
compatibility for this tier is confirmed only at actual instance-creation time — not
testable via a list call, and instance creation is still not authorized. No alternate
tier is needed unless creation itself rejects `db-f1-micro`.

**Bucket created**: `gs://pfi-backend-durable-dev` — `us-central1`, `STANDARD`, uniform
bucket-level access, public access prevention `enforced`, versioning off. (GCS's default
7-day soft-delete retention applied automatically at creation — a different mechanism
from versioning, not something this step turned on deliberately, and it does not conflict
with "versioning: disabled".)

**Bucket IAM** (verified via `get-iam-policy` after every binding): `pfi-backend-runtime@...`
and `pfi-ai-runtime@...` each hold exactly `roles/storage.objectCreator` +
`roles/storage.objectViewer`, scoped to this bucket only. No `allUsers`, no
`allAuthenticatedUsers`, no `storage.admin`. (The bucket also carries GCS's automatic
`legacyBucketOwner`/`legacyObjectOwner`/`legacyBucketReader`/`legacyObjectReader`
bindings for `projectEditor`/`projectOwner`/`projectViewer` — a Cloud Storage default
applied to every new bucket, not something granted by this step; worth a follow-up
hygiene pass since the default compute SA already holds project-wide `roles/editor`.)

**Storage smoke test**: uploaded a non-sensitive text file to `smoke/smoke.txt` (owner
credentials — the account running this session lacks `iam.serviceAccountTokenCreator`
on either runtime SA, so a true impersonated-as-the-SA write wasn't possible; IAM
correctness was instead verified by direct policy inspection above), read it back,
confirmed identical SHA-256, deleted it. Bucket confirmed empty (`0 B`) afterward.

**Secrets created** (no values recorded anywhere in this repo or report):
- `pfi-dev-database-url` — container created, **0 versions** (`PENDING_VALUE` — Cloud
  SQL does not exist yet, so no real connection string exists to load; loading a fake
  one was explicitly disallowed).
- `pfi-dev-jwt-secret` — 1 version, `enabled`, generated locally with
  `openssl rand -base64 48` and piped directly into `gcloud secrets create --data-file=-`
  (never written to a file, never printed).

**Secret IAM**: `pfi-backend-runtime@...` holds `roles/secretmanager.secretAccessor` on
each secret individually (not project-wide). `pfi-ai-runtime@...` was given no secret
access (no evidence it needs DB/JWT secrets).

**Cost incurred by this step**: bucket at 0 B (negligible), 2 secrets (1 with a version,
1 empty) — Secret Manager bills ~US$0.06/active-version/month, so ~US$0.06/month total
for the one loaded version. No Cloud SQL, no Cloud Run changes. See the updated §13
cost gate below for the still-pending Cloud SQL number.

## 0b. Stage 3 status (2026-09-22, executed — Cloud SQL DEV created)

`CLOUD_DEPLOYMENT_STAGE_3_CLOUD_SQL` created the first fixed-cost (non-scale-to-zero)
resource in this plan. **This instance now accrues ~US$11–13/month, 24/7, until it is
deleted.**

**Instance**: `pfi-postgres-dev`, state `RUNNABLE`.

| | |
|---|---|
| Database version | `POSTGRES_15` (installed `15.19`) |
| Edition | `ENTERPRISE` |
| Tier | `db-f1-micro` |
| Region / zone | `us-central1` / `us-central1-c` |
| Availability | `ZONAL` |
| Storage | 10 GB `PD_SSD`, autoresize disabled |
| Backups | disabled |
| PITR | disabled |
| Deletion protection | `true` |
| Connection name | `pfi-asplanatti-fabrello-v1:us-central1:pfi-postgres-dev` |
| Public IP | `34.172.44.200` (present, per the pre-existing Terraform plan's `ipv4Enabled=true`) |
| Authorized networks | **none configured** — the public IP exists but is unreachable over raw TCP; Cloud SQL itself reports "Configuring authorized network or using CloudSQL auth proxy or language connectors is a prerequisite for connecting to Public IP" |
| `requireSsl` / `sslMode` | `false` / `ALLOW_UNENCRYPTED_AND_ENCRYPTED` (default; flagged below, not changed — not in this stage's authorized action list) |
| Creation timestamp (cost baseline start) | `2026-09-22T17:33:08Z` |

**Network exposure review**: confirmed no `0.0.0.0/0` and no other entries in authorized
networks — nothing added by this stage. The only connection made during this stage went
through the **Cloud SQL Auth Proxy** (`gcloud components install cloud-sql-proxy`,
tunneled via the Cloud SQL Admin API using this session's own IAM identity, never via a
raw TCP connection to the public IP). This is the "supported by current architecture"
path requested: Backend's `PostgresStudyRepository`/`PostgresDurableObjectAssetRepository`
etc. only know how to consume a plain `jdbc:postgresql://host:port/db?user=&password=`
URL (confirmed — no `com.google.cloud.sql:*-socket-factory` dependency in `pom.xml`), so
for Cloud Run to reach this instance in Stage 4 it will need **either** (a) a Serverless
VPC Access connector + Private IP on this instance (TCP, no code change, but a new
billed resource not authorized in this stage), **or** (b) adding the Cloud SQL socket
factory dependency and switching to Cloud Run's native `--add-cloudsql-instances`
integration (a code change). Neither was decided or executed here — flagged for the
Stage 4 authorization request.

**Application database**: `pfi` — created, matching the name already fixed in the
pre-existing (never-applied) Terraform plan (`docs/cloud/CLOUD_05_TERRAFORM_PLAN_OUTPUT_F1MICRO.txt`)
rather than the `pfi_backend` name used only as a local-dev-compose default (Backend's
code does not hardcode a database name — it consumes whatever `DATABASE_URL` gives it —
so there was no code-mandated name to defer to; the existing Terraform plan's name was
treated as the standing decision).

**Application user**: `pfi_app` — created (first attempt used a `tr`-mangled password
that silently produced no user, caught by checking `gcloud sql users list` rather than
trusting an unconditional success echo; the user was then created for real and its
password set explicitly with a clean `openssl rand -hex 24` value, verified end-to-end
by the connectivity test below).

**DB secret** (`pfi-dev-database-url`): now holds a real, working
`jdbc:postgresql://<public-ip>:5432/pfi?user=pfi_app&password=...&sslmode=require`
connection string as its one `enabled` version (version 2; version 1, created with the
mismatched password from the failed first attempt, was destroyed). No secret value
appears anywhere in this repo, this document, or any report.

**Secret IAM**: unchanged from Stage 2 — `pfi-backend-runtime@...` holds
`secretAccessor` on `pfi-dev-database-url`.

**Cloud SQL IAM**: `pfi-backend-runtime@...` granted `roles/cloudsql.client` at the
project level (matches the pre-existing Terraform plan's planned binding). SA now holds
exactly `roles/run.invoker` + `roles/cloudsql.client` — no Cloud SQL Admin, no Editor,
no Owner.

**Connectivity test**: PASS, via Cloud SQL Auth Proxy on `127.0.0.1:5433` (localhost
only) + `psql` (run from the `postgres:16-alpine` image already used by the local
Backend stack, connecting to `host.docker.internal:5433`):
- `SELECT version()` → `PostgreSQL 15.19 on x86_64-pc-linux-gnu, ...`
- `CREATE TABLE p0b_connectivity_check(...)`, one `INSERT ... RETURNING`, one
  `SELECT count(*)` (returned `1`), then `DROP TABLE` — table does not exist afterward.
  No real/sensitive data touched the instance.
- Proxy stopped afterward; confirmed via `netstat`/`tasklist` that neither the process
  nor the listening port remain.

**Migration readiness**: 20 migration files under `docs/migrations/`, lexicographically
ordered by filename (`V20260922_023_durable_object_assets.sql` sorts last, correctly),
tracked via an idempotent `schema_migrations` table with advisory-lock concurrency
protection (`SqlMigrationRunner.java`). Nothing here is PostgreSQL-16+-specific; all 20
files use standard DDL compatible with `15.19`. The next Backend process that connects
with `PFI_PERSISTENCE_MODE=postgres` against this instance will auto-apply all 20 on
first connection — confirmed ready, **not run against this instance in this stage**
(no Backend redeploy/connection was authorized).

## 0c. Stage 4 status (2026-09-22, executed — code wired, redeploy blocked on a real finding)

`CLOUD_DEPLOYMENT_STAGE_4_BACKEND_SQL_GCS_WIRING` added the Cloud SQL Java Connector
dependency, built and pushed a new Backend image, and repeatedly attempted a live Cloud
Run redeploy. **Live traffic was never left on the new revision** — every attempt that
looked unsafe was rolled back within seconds, and `pfi-backend-dev` is currently serving
100% traffic from the original revision `pfi-backend-dev-00002-jwd` (`PFI_PERSISTENCE_MODE=memory`),
exactly as before this stage.

### Network path decision, confirmed working

Cloud SQL Java Connector, no VPC connector, no private IP — **confirmed correct**. The
`pfi-dev-database-url` secret was rewritten (version 3; version 2's public-IP TCP URL was
left in place, not destroyed) to:
`jdbc:postgresql:///pfi?cloudSqlInstance=pfi-asplanatti-fabrello-v1:us-central1:pfi-postgres-dev&socketFactory=com.google.cloud.sql.postgres.SocketFactory&user=pfi_app&password=...`
No Java code changes were needed for this — confirmed by audit that every
`PostgresXxxRepository`/`Service`'s `toJdbcUrl()` already passes any `jdbc:postgresql://`-prefixed
string through unchanged, and this shape matches that prefix. Added dependency:
`com.google.cloud.sql:postgres-socket-factory:1.20.1` (commit `1db7a90`).

### What was proven to work

- **Cloud SQL connectivity, end-to-end, twice independently**: once via `java -jar` run
  locally against the real secret value (Spring context started in ~25-31s, all 20
  migrations applied, confirmed via `psql` — `domain_durable_object_assets` and 28 other
  tables present, `schema_migrations` has 20 rows), and again via the deployed Cloud Run
  revision itself (multiple `STARTUP HTTP probe succeeded ... status=200` log lines
  across several redeploy attempts).
- **The pushed image under Cloud-Run-matching constraints, locally**: `docker run
  --memory=512m --cpus=1` with the real `DATABASE_URL` secret (via mounted ADC) served
  15 consecutive `/api/system/health` requests over ~45s, memory flat at ~200MiB/512MiB
  the whole time. Rules out the container image, the dependency, or the JDBC URL shape
  as the problem.
- **The Cloud Run revision, when reached via the `traffic-tag: stage4-cloudsql-gcs`
  header on the stable base URL** (an alternate, officially-supported routing path that
  bypasses the separate tag-subdomain and the "active/default serving" path): 200 OK,
  fast (~0.25-0.32s, no cold start), repeatedly, both before and after the failed
  cutover attempts below. This also verified, safely and without touching real traffic:
  demo-doctor auth works (JWT secret binding correct), and the invalid-DICOM regression
  still holds — a non-ZIP payload to `POST /api/ai/studies` returns 400
  (`"El archivo del estudio no parece un ZIP valido"`), never 500/502.

### What failed, and the real finding

Every attempt to serve traffic through the **separate tag-subdomain URL**
(`stage4-cloudsql-gcs---pfi-backend-dev-....run.app`) or through a **100%
`update-traffic` cutover on the base URL** failed with `503`/connection-reset/timeout,
across multiple redeploy variations tried in sequence to isolate the cause:
`execution-environment` default (gen1) vs explicit `gen2`; memory `512Mi` vs `1Gi`;
`min-instances` `0` vs `1`. None of these changes fixed it, and the failure was absent
both locally (identical image, identical memory cap) and via the `traffic-tag`-header
routing path.

That isolation points to a specific, real gap: **the application opens a brand-new
`DriverManager.getConnection(...)` per repository call, with no connection pool**
(`PostgresStudyRepository`, `PostgresDurableObjectAssetRepository`, and five other
`PostgresXxx` classes each do this independently — pre-existing pattern, not introduced
by this stage). That was cheap enough over a plain TCP socket to never matter. The Cloud
SQL Java Connector's per-connection setup is materially more expensive (an mTLS
handshake per connection, backed by the connector's credential/certificate machinery).
Cloud Run's load balancer runs its own backend-service health checking against the
*actively serving* revision — separate from, and with different concurrency
characteristics than, both the container's own `--startup-probe` and an occasional
ad-hoc `curl`. The working hypothesis is that this concurrent health-check + real-traffic
load, only present once a revision is the **active/100%** target, drove enough
simultaneous connector handshakes on a single vCPU to exhaust the request queue and
produce exactly what was observed: healthy in isolation, healthy under sustained serial
load, unhealthy the moment it becomes the thing Cloud Run's LB is actively
health-checking as primary.

**This is not confirmed root cause** — it is the strongest hypothesis after ruling out
the sandbox generation, memory, and cold-start explanations with direct evidence against
each. The concrete, actionable fix is connection pooling (e.g., HikariCP, or at minimum
a shared `DataSource` per `PostgresXxx` class instead of a raw `DriverManager.getConnection()`
per call) — a real code change, correctly out of scope for a same-day Stage 4 wiring
pass, and not attempted here.

### Rollback performed (twice)

1. After the first `100%` cutover attempt (revision `pfi-backend-dev-00009-pew`) failed
   the base-URL check 3 seconds post-cutover: `update-traffic --to-revisions=pfi-backend-dev-00002-jwd=100`,
   verified `200 {"status":"ok"}` immediately after.
2. After the second `100%` cutover attempt (revision `pfi-backend-dev-00013-zam`, gen2)
   failed repeatedly over ~50s post-cutover: same rollback command, same immediate
   `200 OK` verification.

No Cloud SQL, bucket, or secret was touched by either rollback — only Cloud Run traffic
routing.

### Resources changed this stage

- Backend `pom.xml` + one new migration-free dependency (commit `1db7a90`, pushed to
  `cloud-p0-durable-asset-persistence`).
- New image pushed: `us-central1-docker.pkg.dev/pfi-asplanatti-fabrello-v1/pfi-containers/backend:backend-cloud-sql-gcs-1db7a90`
  (digest `sha256:4fcfb0f036c0712beaeddd97f784536f9bce34d20539e4547e9bb9967fde8d94`, 463MB).
  Previous images (`dee0826c...` and earlier) left untouched.
- `pfi-dev-database-url` secret: version 3 added (Cloud SQL Connector JDBC URL); version
  2 (public-IP JDBC URL) left in place, not destroyed.
- 6 Cloud Run revisions created during iteration (`00003` through `00013`, several
  superseded by later fixes), all currently at 0% traffic, tagged revision pinned to
  `00013-zam` (the gen2/512Mi build — healthy via `traffic-tag` routing, not yet safe
  for 100% traffic per the finding above).
- **`pfi-backend-dev`'s live traffic is unchanged: 100% on `pfi-backend-dev-00002-jwd`.**

**Cost baseline for later measurement**: tier `db-f1-micro`, 10 GB provisioned, DB size
at creation ~8 MB (fresh `pfi` database, no application data), 0 active connections at
rest, creation timestamp `2026-09-22T17:33:08Z` — starting point for the Stage 2/§15
cost-measurement plan once Stage 4 wires Backend to this instance.

## 1. Current GCP inventory (as of this read)

| Item | Value |
|---|---|
| Project | `pfi-asplanatti-fabrello-v1` (number `810538429027`), `ACTIVE` |
| Billing | enabled, account `012206-071130-92E8E1` |
| Region used throughout | `us-central1` |
| Enabled APIs relevant here | `artifactregistry`, `run`, `storage` (api/component/json), `iam`, `iamcredentials`, `sts`, `compute`, `logging`, `monitoring` |
| **Disabled APIs needed for this plan** | `secretmanager.googleapis.com` (Secret Manager), `sqladmin.googleapis.com` (Cloud SQL Admin) — **zero secrets and zero Cloud SQL instances exist because these APIs were never turned on** |

### Cloud Run (all three services already deployed — corrects the assumption that AI Cloud Run is undeployed)

| Service | Image | CPU / RAM | Concurrency | min/max instances | Ingress | Invoker IAM | Service account |
|---|---|---|---|---|---|---|---|
| `pfi-ai-module` | `pfi-containers/pfi-ai-module@sha256:c54475f2...` (built 2026-08-10 — **predates** the durable-storage commits `b2526da`/`87eae58`, i.e. the deployed AI image does NOT have `object_storage.py` yet) | 4 vCPU / 2Gi | 1 | 0 / 1 | all (network-open) | **no bindings — effectively private**, only an identity with `run.invoker` can call it | `pfi-ai-runtime@...` |
| `pfi-backend-dev` | `pfi-containers/backend:dee0826c...` | 1 vCPU / 512Mi | 80 | 0 / 2 | all | `allUsers` — **public** | `pfi-backend-runtime@...` |
| `pfi-frontend-dev` | `pfi-containers/frontend:1c77231e...` | 1 vCPU / 256Mi | 80 | 0 / 2 | all | `allUsers` — **public** | `pfi-frontend-runtime-dev@...` |

Backend-dev's current env vars: `PFI_CORS_ALLOWED_ORIGINS`, `PFI_AUTH_DEMO_ENABLED=true`,
`PFI_PERSISTENCE_MODE=memory`. **No `DATABASE_URL`, no `PFI_AI_SERVICE_URL`, no
`PFI_AI_AUTH_MODE`, no `PFI_STORAGE_*`** — Backend-dev today runs in-memory, does not
call the deployed AI Cloud Run at all, and has no durable storage wired. AI-module's env
vars only cover the P10.7 checkpoint path/device — no `PFI_STORAGE_*` either.

**Already-useful finding**: `pfi-backend-runtime@...` already holds project-wide
`roles/run.invoker` (see §4). That is half of "Backend → AI Google ID Token auth" —
the IAM permission exists; the Backend code path/env wiring to actually mint and send
an ID token to `pfi-ai-module`'s URL does not (see §7).

### Artifact Registry

- Repository `pfi-containers` (Docker, STANDARD), us-central1, 535.5 MB total.
- Images present: `backend` (3 manifests, ~106 MB), `frontend` (3 manifests, ~21 MB),
  `pfi-ai-module` (3 manifests, ~434 MB compressed).
- WIF: `workloadIdentityPools/github-pool` (`ACTIVE`) federates GitHub Actions OIDC —
  no service-account JSON key used for CI push. `pfi-github-artifact-pusher@...` holds
  only `roles/artifactregistry.writer` on this one repository (least privilege, already
  correct).

### Cloud Storage

- **One bucket exists**: `gs://pfi-rm-lumbar-artifacts-2026-ef` — `us-central1`,
  `STANDARD`, uniform bucket-level access, public access prevention `enforced`, no
  versioning, soft-delete retention `0s` (disabled).
- **Current use**: read-only GCS FUSE mount into `pfi-ai-module` at
  `/mnt/pfi-artifacts` for the P10.7 model checkpoint. `pfi-ai-runtime@...` has
  `roles/storage.objectViewer` on it; `pfi-training-vm@...` has `roles/storage.objectUser`
  (read/write, for training). **Nothing writes study data here today.**
- Per instruction: **do not reuse this bucket for study/output/report data** — it is a
  model-artifact bucket with its own IAM shape (training VM has write access, which a
  clinical-data bucket must not grant). A new, separate bucket is proposed in §3.

### Secret Manager

API disabled. 0 secrets. (Backend/AI currently read all config, including
`PFI_AUTH_JWT_SECRET`, from plain Cloud Run env vars — not secret-backed yet.)

### Cloud SQL

API disabled. 0 instances. **However, a complete, ready-to-apply Terraform plan already
exists** (`docs/cloud/CLOUD_05_TERRAFORM_PLAN_OUTPUT_F1MICRO.txt`, plan-only, never
applied) for:

```
google_sql_database_instance.this["pfi-postgres-dev"]:
  database_version    = POSTGRES_15
  edition              = ENTERPRISE
  tier                 = db-f1-micro
  availability_type    = ZONAL
  disk_type            = PD_SSD
  disk_size            = 10 (GB)
  disk_autoresize      = false
  deletion_protection  = true
  backup_configuration.enabled = false
  backup_configuration.point_in_time_recovery_enabled = false
  ip_configuration.ipv4_enabled = true

google_sql_database.app["pfi"]:
  name = "pfi", instance = "pfi-postgres-dev"

google_secret_manager_secret.this["pfi-dev-database-url"]  (planned)
google_project_iam_member: pfi-backend-runtime -> roles/cloudsql.client  (planned)
```

This matches the task's target config exactly (§6). It was never applied because
`sqladmin.googleapis.com` was never enabled.

### Service accounts (all pre-existing, none created here)

| SA | Purpose | Notable bindings found |
|---|---|---|
| `pfi-backend-runtime@...` | Backend Cloud Run identity | project `roles/run.invoker` (can call private Cloud Run) |
| `pfi-ai-runtime@...` | AI Cloud Run identity | project `roles/storage.viewer`; bucket `roles/storage.objectViewer` on the artifacts bucket |
| `pfi-frontend-runtime-dev@...` | Frontend Cloud Run identity | none beyond default |
| `pfi-github-artifact-pusher@...` | CI image push via WIF | repo-scoped `roles/artifactregistry.writer` only |
| `pfi-training-vm@...` | Model training VM | bucket `roles/storage.objectUser` on artifacts bucket |
| `810538429027-compute@...` | Compute Engine default SA | project `roles/editor` (broad — pre-existing, not created by this plan, flagged as a hygiene item, not this stage's problem to fix) |

## 2. Confirming existing services vs. the "not deployed" assumption

The `CLOUD_DEPLOYMENT_STAGE_1_PREP` instructions expected to find Backend-DEV and
Frontend-DEV `DEPLOYED` and AI Cloud Run **not yet present**. The live read shows all
**three** already deployed, but:

- The deployed `pfi-ai-module` image predates durable object storage (built before
  `b2526da`), so pushing the current AI image (with `object_storage.py`) is still real,
  necessary work (§8–9), just a re-deploy of an existing service rather than a first
  deploy.
- Backend-dev is deployed but disconnected from both Cloud SQL (runs in-memory) and the
  AI Cloud Run service (no URL/audience configured) — so "Backend Cloud Run DEV:
  DEPLOYED" is true, but "wired end-to-end" is not.

## 3. Cloud Storage plan

**Do not create a bucket automatically.** Proposal for explicit review before any
`[NOT EXECUTED]` command below is run:

- Name: `pfi-backend-durable-dev` (or `pfi-rm-lumbar-studies-dev` if a name closer to
  the existing artifacts bucket's convention is preferred — pick one before creating,
  since a bucket name is globally unique and can't be renamed after the fact).
- Region: `us-central1` (co-located with Cloud Run/Cloud SQL — avoids cross-region
  egress).
- Storage class: `STANDARD` (regional) — DEV workload is small and read/write-active,
  not archival; `STANDARD` regional is the low-cost correct choice here, not Nearline/
  Coldline.
- Versioning: **off** for DEV (object versions are immutable content-addressed by
  `sha256` in the object key's parent asset row already — versioning would only add
  storage cost without a corresponding integrity benefit at this stage).
- Lifecycle: none for `studies/`/`reports/` (durable, must persist); a lifecycle rule
  deleting objects under a `tmp/` prefix after N days is worth adding once anything
  actually writes there (nothing does yet).
- Uniform bucket-level access: on (matches the existing bucket, avoids legacy ACL
  drift).
- Public access prevention: `enforced` (matches the existing bucket; this is clinical
  data, never public).

Layout (matches `ObjectKeys.java` already merged in `0b086db`):

```
studies/{studyId}/inputs/{assetId}/{filename}     <- MUST_PERSIST (raw DICOM, legal input record)
studies/{studyId}/outputs/{runId}/{filename}      <- AI-module-owned (runs/{runId}/... today; see design doc §2)
studies/{studyId}/reports/{reportId}/{filename}   <- not wired yet (design doc §9)
```

Classification (per `docs/cloud/CLOUD_DURABLE_STORAGE_DESIGN.md` §9, restated here):

- **MUST_PERSIST**: `studies/*/inputs/*` (raw DICOM ZIP), signed-off structured report
  JSON once wired.
- **SHOULD_PERSIST**: rendered report PDF (regenerable, but exact-bytes provenance has
  value).
- **CAN_REGENERATE**: AI intermediate rendering artifacts, chart fonts/images.
- **TEMPORARY**: none currently written by either service; no lifecycle rule needed yet.

`[NOT EXECUTED -- requires explicit authorization]`
```bash
gcloud storage buckets create gs://pfi-backend-durable-dev \
  --project=pfi-asplanatti-fabrello-v1 \
  --location=us-central1 \
  --default-storage-class=STANDARD \
  --uniform-bucket-level-access \
  --public-access-prevention
```

## 4. IAM — storage (proposed, not applied)

Two new bindings, both scoped to the **new** bucket only (never the existing artifacts
bucket, never `roles/storage.admin`):

`[NOT EXECUTED -- requires explicit authorization]`
```bash
gcloud storage buckets add-iam-policy-binding gs://pfi-backend-durable-dev \
  --member="serviceAccount:pfi-backend-runtime@pfi-asplanatti-fabrello-v1.iam.gserviceaccount.com" \
  --role="roles/storage.objectCreator"

gcloud storage buckets add-iam-policy-binding gs://pfi-backend-durable-dev \
  --member="serviceAccount:pfi-backend-runtime@pfi-asplanatti-fabrello-v1.iam.gserviceaccount.com" \
  --role="roles/storage.objectViewer"

gcloud storage buckets add-iam-policy-binding gs://pfi-backend-durable-dev \
  --member="serviceAccount:pfi-ai-runtime@pfi-asplanatti-fabrello-v1.iam.gserviceaccount.com" \
  --role="roles/storage.objectCreator"

gcloud storage buckets add-iam-policy-binding gs://pfi-backend-durable-dev \
  --member="serviceAccount:pfi-ai-runtime@pfi-asplanatti-fabrello-v1.iam.gserviceaccount.com" \
  --role="roles/storage.objectViewer"
```

No delete permission for either SA — neither's current code path calls
`ObjectStorage.delete` in the DICOM/report flow. `roles/storage.admin` is not used
anywhere in this plan.

## 5. Secret Manager plan (not created)

| Name | Type | Value source |
|---|---|---|
| `pfi-dev-database-url` | SECRET | Cloud SQL connection string (already named in the existing Terraform plan) |
| `pfi-dev-jwt-secret` | SECRET | replaces the current plain-env `PFI_AUTH_JWT_SECRET` on `pfi-backend-dev` |
| LLM/API credentials | SECRET | not applicable yet — `pfi.llm.enabled=false` by default, no external LLM provider wired |
| `bucket name` (`PFI_STORAGE_GCS_BUCKET`) | NON_SECRET | plain env var |
| `project id` | NON_SECRET | plain env var / already implicit in Cloud Run metadata |
| service URLs (`PFI_AI_SERVICE_URL`, `BACKEND_URL`) | NON_SECRET | plain env var |
| `PFI_AI_SERVICE_AUDIENCE` | NON_SECRET | plain env var (it's the AI Cloud Run URL, not a credential) |
| `PFI_STORAGE_PROVIDER` | NON_SECRET | plain env var |
| DB name/user | NON_SECRET | low sensitivity; only the full connection string (with password) is SECRET |

`[NOT EXECUTED -- requires explicit authorization, and requires enabling
secretmanager.googleapis.com first]`
```bash
gcloud services enable secretmanager.googleapis.com --project=pfi-asplanatti-fabrello-v1
gcloud secrets create pfi-dev-database-url --project=pfi-asplanatti-fabrello-v1 --replication-policy=automatic
gcloud secrets create pfi-dev-jwt-secret --project=pfi-asplanatti-fabrello-v1 --replication-policy=automatic
```

## 6. Cloud SQL — final check

Target (unchanged from the existing Terraform plan, re-verified against this read):
PostgreSQL 15, `ENTERPRISE` edition, `db-f1-micro`, `us-central1`, `ZONAL`, 10 GB
`PD_SSD`, no HA, no PITR, backups disabled, `deletion_protection = true`.

**Resolved in Stage 2** (2026-09-22): `sqladmin.googleapis.com` enabled,
`gcloud sql tiers list --project=pfi-asplanatti-fabrello-v1` run (read-only, zero
instances created). `db-f1-micro` **is available in `us-central1`** (614.4 MiB RAM,
shared-core, 3 TiB max disk quota for this tier). The Cloud SQL Tiers API does not
expose per-engine/per-edition compatibility (no field distinguishes MySQL/Postgres/SQL
Server or Enterprise/Enterprise Plus support per tier), so `POSTGRES_15` +
`ENTERPRISE`-edition compatibility for `db-f1-micro` is only fully confirmed at actual
instance-creation time — still gated behind Cloud SQL creation authorization, not this
read-only tier check. No alternate tier is proposed unless creation itself rejects it.

**Estimated monthly fixed cost** (list-price estimate, not pulled from a live Billing
API call in this pass — verify with the Cloud Billing Catalog before committing):

| Item | Estimate |
|---|---|
| `db-f1-micro` instance, `ZONAL`, `us-central1`, `ALWAYS` activation policy | ~US$9–11/month |
| 10 GB `PD_SSD` | ~US$1.70/month |
| Backups/PITR | US$0 (both disabled) |
| **Total Cloud SQL fixed** | **~US$11–13/month**, running 24/7 regardless of study volume |

This is the plan's single largest **fixed** (non-scale-to-zero) monthly cost, which is
why it gets its own cost gate (§12) before creation.

## 7. Backend Cloud config (future env, not applied)

```
# Storage
PFI_STORAGE_PROVIDER=gcs
PFI_STORAGE_GCS_BUCKET=pfi-backend-durable-dev

# Cloud SQL (via Secret Manager reference, not plain env)
DATABASE_URL=<from secret pfi-dev-database-url>
PFI_PERSISTENCE_MODE=postgres

# AI service
PFI_AI_SERVICE_URL=https://pfi-ai-module-<hash>-uc.a.run.app
PFI_AI_AUTH_MODE=google-id-token
PFI_AI_SERVICE_AUDIENCE=https://pfi-ai-module-<hash>-uc.a.run.app
```

Fail-closed confirmation (both already true in code, re-verified this session):

- **GCS fail-closed**: `GcsObjectStorage` constructor throws `ObjectStorageException`
  immediately if `PFI_STORAGE_GCS_BUCKET` is blank (`GcsObjectStorageTest`,
  `ObjectStorageConfigTest` — no silent filesystem fallback).
- **AI auth fail-closed**: `AiServiceAuthMode`/`GoogleIdTokenAiServiceAuthorizationProvider`
  (pre-existing) already gate `google-id-token` mode; `pfi-backend-runtime@...` already
  holds the `roles/run.invoker` needed to mint a usable identity token for the private
  `pfi-ai-module` service (§1) — the missing piece is only the three env vars above,
  not IAM.

## 8. AI image

| | |
|---|---|
| Deployed image (Cloud Run, current) | `pfi-ai-module@sha256:c54475f2...`, ~434 MB compressed in Artifact Registry, built 2026-08-10 |
| Current source build (this session, local only, **not pushed**) | `pfi-ai-module:p0b-restart-test`, 2.17 GB uncompressed (Docker-reported) — includes `google-cloud-storage` (added in `b2526da`) |
| Build result | succeeded locally this session (`docker build`, exit 0) — see the P0B evidence chapter |
| Est. Artifact Registry storage delta | current repo total 535 MB; a fourth `pfi-ai-module` manifest of comparable compressed size to the existing one (~430–450 MB) would roughly double this repo's total to ~970 MB–1 GB — still under most free-tier thresholds, a few cents/month beyond that |
| Startup requirement (as already configured in prod) | `startupProbe`: `tcpSocket:8000`, `failureThreshold=1`, `periodSeconds=240`, `timeoutSeconds=240` — i.e. Cloud Run already budgets up to 240s for the container to open the port |
| CPU/RAM (as already configured in prod) | 4 vCPU / 2 GiB |

`[NOT EXECUTED -- requires explicit authorization]`
```bash
docker tag pfi-ai-module:p0b-restart-test \
  us-central1-docker.pkg.dev/pfi-asplanatti-fabrello-v1/pfi-containers/pfi-ai-module:p0b-durable-storage
docker push \
  us-central1-docker.pkg.dev/pfi-asplanatti-fabrello-v1/pfi-containers/pfi-ai-module:p0b-durable-storage
```

## 9. AI Cloud Run target

The service already exists with a config close to what would be proposed from
scratch. Recommended values for the **next revision** (deploying the durable-storage
image), changes from current marked:

| Setting | Current (deployed) | Proposed | Why |
|---|---|---|---|
| min instances | 0 (unset) | 0 | keep scale-to-zero; DEV traffic is not continuous |
| max instances | 1 | 1 or 2 | user's own p95/peak memory figures (~801 MiB / ~941 MiB) weren't found in this repo's docs during this read — treating them as given, not independently re-verified here; 2Gi already comfortably covers that peak with headroom, no change needed yet |
| CPU | 4 | 4 | unchanged — real-baseline inference is CPU-bound (see §10) |
| Memory | 2Gi | 2Gi | unchanged, already covers the given peak figure with ~1.1 GiB headroom |
| Concurrency | 1 | 1 | unchanged — one inference request per instance at a time is correct for a CPU-bound model, avoids memory contention |
| Timeout | 300s | 300s | unchanged, already generous for inference |
| Ingress | all (IAM-gated) | all (IAM-gated), or switch to `internal-and-cloud-load-balancing` if the Backend is the only caller and no load balancer is planned | tightens network exposure without touching the (already-correct) empty invoker policy |
| Env: `PFI_STORAGE_PROVIDER` | unset | `gcs` | activates durable storage in Cloud Run |
| Env: `PFI_STORAGE_GCS_BUCKET` | unset | `pfi-backend-durable-dev` (or the AI-owned equivalent — see note below) | required, fails closed if missing |

Note: the AI module's object keys are `runs/{runId}/...`, the Backend's are
`studies/{studyId}/...` — they can share one bucket (different prefixes) or use two
buckets. Sharing one bucket is simpler operationally and still keeps IAM separate per
prefix isn't natively enforceable by GCS IAM (GCS IAM is bucket-level, not
prefix-level) — so if strict separation is wanted later, two buckets are the correct
mechanism, not one bucket with prefix-scoped IAM (which GCS doesn't support without
Cloud Storage's newer "managed folders" feature). **Decision needed before §3's create
command is run**: one shared bucket (simpler, both SAs get object create/view on it) or
two (stronger isolation, double the buckets to manage). This plan defaults to one
shared bucket in §3–4 for DEV; flag if two is preferred.

## 10. AI startup risk

| Risk | What to measure on first real Cloud Run deploy |
|---|---|
| Image pull time | Cloud Run pulls from Artifact Registry in-region — should be seconds, not the bottleneck; confirm via the deploy operation's own timing |
| Cold start | `startupProbe` already budgets 240s; measure actual time-to-first-`/health`-200 on a cold (post scale-to-zero) request |
| Checkpoint/model load | `sagittal_spider` + `axial_t2_alkafri` load from the image itself (baked in per `Dockerfile`); P10.7 loads from the GCS FUSE mount — FUSE read latency on cold start is worth timing separately from in-image checkpoint load |
| Memory | watch actual peak via Cloud Monitoring (`run.googleapis.com/container/memory/utilizations`) against the 2Gi limit during a real inference request, not just the given p95/peak figures |
| CPU | watch `run.googleapis.com/container/cpu/utilizations` during real-baseline inference — 4 vCPU was sized generously; confirm it's not oversized once real numbers exist |
| Health endpoint | `/health` already exists and is what the startupProbe/local Docker healthcheck use — reuse for Cloud Run's own health checks |
| Request timeout | 300s configured; measure actual real-baseline inference wall-clock time against it |

## 11. Deployment sequence

Unchanged from the instructions' proposed order — nothing in this read-only pass found
evidence to reorder it, since Backend/Frontend Cloud Run already exist and don't block
CLOUD-01..04:

```
CLOUD-01  Storage bucket + IAM                          (§3, §4)
CLOUD-02  Secret Manager (enable API + 2 secrets)        (§5)
CLOUD-03  Cloud SQL (enable API + verify tier + create)  (§6)
CLOUD-04  Backend wiring: redeploy pfi-backend-dev with
          DATABASE_URL/PFI_STORAGE_*/PFI_AI_* env         (§7)
CLOUD-05  Push durable-storage AI image                  (§8)
CLOUD-06  Redeploy pfi-ai-module with PFI_STORAGE_* env   (§9)
CLOUD-07  Confirm Backend -> AI Google ID Token call
          succeeds end-to-end against the new revision    (§7)
CLOUD-08  Cloud E2E: upload synthetic fixture, run
          pipeline, reprocess, verify durable objects      (§14)
CLOUD-09  Restart/persistence validation on Cloud Run
          (force a new revision, confirm data survives)    --
CLOUD-10  Cost/performance measurement                     (§15)
```

## 12. Cost gates (template — fill in and confirm before each `[NOT EXECUTED]` command runs)

| Resource | Expected config | Est. monthly fixed | Est. variable | Scale to zero? | Deletion command | Risk |
|---|---|---|---|---|---|---|
| GCS bucket — **CREATED** 2026-09-22 | STANDARD regional, no versioning, currently 0 B | ~US$0.02/GB/mo stored (0 B today = ~US$0) | per-op, negligible at DEV volume | n/a (storage isn't compute) | `gcloud storage buckets delete gs://pfi-backend-durable-dev` (must be empty, or add `--recursive` with the data-loss implications understood) | Low — reversible, currently empty |
| Secret Manager — **CREATED** 2026-09-22 | 2 secrets (`pfi-dev-jwt-secret`: 1 version; `pfi-dev-database-url`: 0 versions, `PENDING_VALUE`) | ~US$0.06/mo (1 active version today) | ~US$0.03/10k accesses | n/a | `gcloud secrets delete <name>` | Low |
| **Cloud SQL `db-f1-micro`** — **CREATED** 2026-09-22T17:33:08Z | ZONAL, 10GB PD_SSD, no backups, instance `pfi-postgres-dev` | **~US$11–13/mo, runs 24/7 starting now** | negligible at DEV query volume | **No** — Cloud SQL does not scale to zero | `gcloud sql instances delete pfi-postgres-dev` (blocked while `deletion_protection=true`; must first `gcloud sql instances patch pfi-postgres-dev --no-deletion-protection`) | **Now the plan's only active fixed-cost item — running since the timestamp above** |
| AI Cloud Run new revision | 4 vCPU/2Gi, min=0 | US$0 idle | per-request CPU/RAM-seconds while serving | **Yes** | `gcloud run services delete pfi-ai-module` (or just stop routing traffic to the new revision) | Low — already scale-to-zero today |
| Backend Cloud Run redeploy | 1 vCPU/512Mi, min=0 | US$0 idle | per-request | Yes | `gcloud run services delete pfi-backend-dev` | Low |
| Artifact Registry image push | ~450 MB new manifest | ~US$0.05–0.10/mo beyond free tier | egress on pull | n/a | `gcloud artifacts docker images delete ...` | Low |

## 13. Rollback plan (not executed)

```bash
# AI Cloud Run: revert traffic to the previous (pre-durable-storage) revision
gcloud run services update-traffic pfi-ai-module --to-revisions=pfi-ai-module-00001-m62=100 \
  --project=pfi-asplanatti-fabrello-v1 --region=us-central1

# Cloud SQL: disable protection, then delete
gcloud sql instances patch pfi-postgres-dev --no-deletion-protection --project=pfi-asplanatti-fabrello-v1
gcloud sql instances delete pfi-postgres-dev --project=pfi-asplanatti-fabrello-v1

# Bucket: empty then delete (irreversible — confirm nothing MUST_PERSIST is still needed first)
gcloud storage rm --recursive gs://pfi-backend-durable-dev/**
gcloud storage buckets delete gs://pfi-backend-durable-dev --project=pfi-asplanatti-fabrello-v1

# Secrets
gcloud secrets delete pfi-dev-database-url --project=pfi-asplanatti-fabrello-v1
gcloud secrets delete pfi-dev-jwt-secret --project=pfi-asplanatti-fabrello-v1

# Unused Artifact Registry images (keep at least one known-good tag)
gcloud artifacts docker images delete \
  us-central1-docker.pkg.dev/pfi-asplanatti-fabrello-v1/pfi-containers/pfi-ai-module:p0b-durable-storage \
  --project=pfi-asplanatti-fabrello-v1
```

`pfi-backend-dev` and `pfi-frontend-dev` are never targeted by this rollback beyond
`update-traffic`-style reverts — no delete command for either in this plan.

## 14. First real Cloud E2E test data

Only the same synthetic, non-sensitive fixture already used for the local restart
evidence: a pydicom-built two-series (sagittal + axial) ZIP, generated the same way as
`ai_service/tests/test_study_zip_keeps_all_series.py` (no real patient data, no
PHI). Do not upload a real patient study to any Cloud resource in Stage 2's first pass.

## 15. Cost measurement plan

Controlled experiment once CLOUD-08 is live:

1. 1 warmup study (absorbs cold start, excluded from the per-study average).
2. 10 studies.
3. 25 studies.
4. 50 studies.

Per run, capture: AI cold-start latency (first request after idle) vs. warm inference
latency; Backend request latency; bytes uploaded (ZIP size) and bytes generated
(overlay/mask/report bytes) per study; Cloud SQL storage growth (`\l+`/`pg_database_size`);
Cloud Storage bucket growth (`gcloud storage du`); Cloud Run CPU/memory utilization
(Cloud Monitoring); request counts; and actual billing line items from Cloud Billing
export once available (not estimated).

## 16. Financial model handoff (not applied to the Excel yet)

The 50-study run's `(Cloud SQL fixed cost prorated for the measurement window +
Cloud Run variable cost for 50 studies + Storage delta) / 50` replaces:

- **LOW**: 0.07 USD/study
- **BASE**: 0.16 USD/study
- **HIGH**: 0.38 USD/study

No change to `GestionadorLatex-Enzo_PFI_SEGUNDA_ENTREGA_WORK`'s financial model in this
pass — that repo was not touched, per instruction.
