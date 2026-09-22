# Cloud Deployment Stage 1 — Real Execution Plan (read-only prep, no writes)

Everything in this document is a plan against the **actual, live state** of
`pfi-asplanatti-fabrello-v1`, gathered via read-only `gcloud`/`terraform plan`-adjacent
inspection on 2026-09-22. No resource was created, modified or deleted while gathering
it. Where a command below would create/modify/delete a billed resource, it is marked
`[NOT EXECUTED -- requires explicit authorization]` and must not be run without that
authorization arriving in a later, separate instruction.

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

**Blocker to resolve before Stage 2, not before**: `db-f1-micro` needs to be confirmed
still orderable for PostgreSQL under the `ENTERPRISE` edition in this project/region —
GCP has been narrowing shared-core tier availability by edition over the last couple of
years and I have not independently verified current tier availability (that requires
`gcloud sql tiers list`, which itself requires enabling `sqladmin.googleapis.com` first
— a project-config change, so it's listed as `[NOT EXECUTED]` here rather than run
during this read-only pass).

`[NOT EXECUTED -- requires explicit authorization]`
```bash
gcloud services enable sqladmin.googleapis.com --project=pfi-asplanatti-fabrello-v1
gcloud sql tiers list --project=pfi-asplanatti-fabrello-v1 --filter="region:us-central1"
```

If `db-f1-micro` is gone, the minimum-equivalent replacement is `db-g1-small` (1 vCPU
shared, 1.7 GB) or the newer `db-perf-optimized-N-1`/`db-custom-1-3840` shared-core
tiers — whichever `gcloud sql tiers list` actually reports as available; do not guess
at a name that isn't returned by that call.

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
| GCS bucket | STANDARD regional, no versioning | ~US$0.02/GB/mo stored (negligible at DEV volume) | per-op, negligible at DEV volume | n/a (storage isn't compute) | `gcloud storage buckets delete gs://pfi-backend-durable-dev` (must be empty, or add `--recursive` with the data-loss implications understood) | Low — reversible, no data until first upload |
| Secret Manager | 2 secrets | ~US$0.12/mo (2 × $0.06/active version) | ~US$0.03/10k accesses | n/a | `gcloud secrets delete <name>` | Low |
| **Cloud SQL `db-f1-micro`** | ZONAL, 10GB PD_SSD, no backups | **~US$11–13/mo, runs 24/7** | negligible at DEV query volume | **No** — Cloud SQL does not scale to zero | `gcloud sql instances delete pfi-postgres-dev` (blocked while `deletion_protection=true`; must first `gcloud sql instances patch pfi-postgres-dev --no-deletion-protection`) | **Highest fixed-cost item in this plan** — the one to get explicit sign-off on before creating |
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
