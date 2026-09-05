# CLOUD-05 — Cloud SQL Design

## Engine and version

**Cloud SQL for PostgreSQL, `POSTGRES_15`.** No MySQL, no engine change — the application already
speaks plain PgJDBC (`org.postgresql:postgresql:42.7.4`). `POSTGRES_15` is a current, standard-support
major version — confirmed via the Cloud Billing Catalog API that it is **not** subject to the
"Extended support" pricing surcharge that now applies to PostgreSQL 9.6–13.

## Tier correction (real finding, not a stale estimate)

The CLOUD-01 Terraform default was `db-f1-micro`, documented at the time as "~$8/month" by historical
approximation. **That default is no longer valid for a new instance.** Verified directly against the
live Cloud Billing Catalog API (`cloudbilling.googleapis.com/v1/services/9662-B51E-5089/skus`,
~15,000 SKUs paged and inspected for `us-central1`): every `db-f1-micro`/`db-g1-small` SKU found is
versioned "Extended support f1-micro/g1-small vNN", tied to already-EOL PostgreSQL major versions
(9.6–13) — shared-core machine types have been removed from new-instance ordering on current major
versions. The smallest currently orderable configuration is a **custom machine type**, billed per
resource, not as a bundled tier.

## Sourced pricing (Cloud Billing Catalog API, `us-central1`/Iowa, captured 2026-09-04)

| Resource | SKU | Rate |
|---|---|---|
| Zonal vCPU | `965E-7C36-08E6` "Cloud SQL for PostgreSQL: Zonal - vCPU in Americas" | $0.0413 / vCPU-hour |
| Zonal RAM | `93DA-3F55-CB04` "Cloud SQL for PostgreSQL: Zonal - RAM in Americas" | $0.0070 / GiB-hour |
| Zonal SSD storage | `903C-B42D-4287` "Zonal - Enterprise Plus Standard Storage" (matches the plain "Standard storage" Postgres SKU at the same $0.17 rate seen on the SQL Server and FDC-trial equivalents) | $0.17 / GiB-month |
| Backups | `46C8-DA82-DF18` "Cloud SQL: Backups in Americas" | $0.08 / GiB-month |
| Regional (HA) vCPU/RAM | `1912-86A6-9950` / `9BE2-CB5B-66F8` | exactly 2× the Zonal rate ($0.0826/vCPU-hr, $0.0140/GiB-hr) |

Regional/HA being exactly double Zonal at every resource is consistent, not a rounding coincidence —
confirms HA in Cloud SQL is priced as a literal second (standby) instance.

## Tier comparison

| Option | Config | vCPU | RAM | Disk | Backup | HA | 24h cost | 7-day cost | 30-day (730h) cost | Stoppable? | Stopped-instance cost | Recommended |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **DEV-min** | `db-custom-1-3840` | 1 | 3.75 GiB | 10 GB PD_SSD | off | ZONAL | ~$1.68 | ~$11.75 | **~$51.01** | Yes (`activation_policy=NEVER`) | ~$1.70/mo (storage only) | **Yes — this stage's default** |
| **DEMO** | `db-custom-1-3840` | 1 | 3.75 GiB | 10 GB PD_SSD | **on** | ZONAL | ~$1.71 | ~$11.98 | ~$51.81 | Yes | ~$1.70/mo | Flip `backup_enabled=true` only when a demo is scheduled |
| **Future prod-grade** | `db-custom-2-7680` | 2 | 7.5 GiB | 20 GB PD_SSD | on | **REGIONAL (HA)** | ~$6.75 | ~$47.27 | ~$205.65 | No (HA defeats the point of stopping) | n/a | Not proposed now — needs its own authorization if the project ever needs real uptime SLAs |

Compute math (DEV-min, 730h/month): `(1 × $0.0413 + 3.75 × $0.0070) × 730h = $0.06755/h × 730h =
$49.31/month` compute + `10 × $0.17 = $1.70/month` storage = **$51.01/month**, or **~$1.68/day**.
Backups off by default (see below) add nothing; turning them on for a DEMO adds `10 × $0.08 =
$0.80/month`.

**This design proposes DEV-min only.** DEMO and future-prod rows are shown for comparison, not
requested.

## Storage, backup, and HA choices

- **Disk**: `PD_SSD`, 10 GB (Cloud SQL's documented minimum) — not sized up without demonstrated need.
- **`disk_autoresize = false`**: an unbounded, surprise storage-cost increase is worse than an
  out-of-space error an operator notices and responds to explicitly, for a DEV database sized in
  single-digit GB.
- **`backup_enabled = false` for DEV**: DEV data is synthetic and reproducible (smoke-test rows, not
  real patient data — no official/real dataset is loaded per the explicit constraint on this stage).
  Losing it costs nothing but re-running the smoke test. $0.80–$1.70/month saved is immaterial at this
  scale, but the principle — no cost without demonstrated value — is the same one CLOUD-04 applied to
  `cpu_idle`/`startup_cpu_boost`. **DEMO should flip this to `true`** before any real demo, since a
  failed demo losing its seeded data mid-presentation is a real cost.
- **`point_in_time_recovery_enabled = false`**: PITR requires backups to already be enabled and adds
  further storage cost for WAL retention — not justified without backups themselves being justified.
- **`availability_type = ZONAL`**: Regional (HA) is confirmed to double both compute and storage cost
  for automatic failover a thesis DEV/DEMO environment does not need — no user-facing SLA exists to
  protect.

## Networking

**Public IP (`ipv4_enabled = true`) with an empty `authorized_networks` list — no
`google_sql_database_instance.settings.ip_configuration.authorized_networks` block at all, and no
Terraform variable exposing one.** This is the permanent design, not an interim convenience: the only
path into the instance is the Cloud SQL Auth Proxy sidecar, which authenticates via the Backend
runtime service account's `roles/cloudsql.client` grant, not by source IP. A bare public IP with zero
authorized networks accepts no unauthenticated TCP connections — Cloud SQL still enforces the
proxy/IAM authentication layer regardless of IP reachability. This avoids standing up a VPC + Private
Service Access connection (real, ongoing complexity this project does not need) while never exposing
Postgres to a raw, IP-allowlisted connection. No `0.0.0.0/0`, no `authorized_networks` entry, ever.

## Credential strategy — no password in Terraform

**No `google_sql_user` resource exists in this design, deliberately.** Two paths were considered:

1. Terraform `google_sql_user` with a literal or `random_password`-generated password — rejected.
   Even with `sensitive = true`, the raw value still lands in `terraform.tfstate` in plaintext
   (`sensitive` only redacts Terraform's own CLI output, never the state file itself) — an explicit,
   named risk the user ruled out without discussion.
2. **Chosen**: the DB user and password are created **out-of-band**, manually, via `gcloud sql users
   create` (or the Cloud Console), never through Terraform, never entering any `.tf` file or state.
   The resulting connection string is then loaded into the `pfi-dev-database-url` Secret Manager
   secret **value** (also out-of-band — see `CLOUD_05_SECRET_MANAGER.md` for the exact commands),
   which Terraform never reads back or re-emits.

This satisfies the hard constraint (no plaintext secret in tfstate) without `random_password`, at the
cost of one manual step per environment — an acceptable tradeoff for a two-environment (DEV/DEMO)
project with no automated environment provisioning pipeline.

## Backend Cloud Run wiring

- `PFI_PERSISTENCE_MODE`: `memory` → `postgres`, gated by `enable_cloud_sql`.
- `DATABASE_URL` and `PFI_AUTH_JWT_SECRET`: sourced from Secret Manager via `secret_env_vars`, gated
  by `enable_cloud_sql && enable_secrets`.
- A new `cloudsql-proxy` sidecar container (`gcr.io/cloud-sql-connectors/cloud-sql-proxy:2.14.0`),
  gated by `enable_cloud_sql`, with a `tcp_socket` startup probe on port 5432 and the main `app`
  container's `depends_on` pointing at it so Cloud Run waits for the proxy to be reachable before
  starting the app.
- **Same image digest.** Only the Cloud Run *revision*'s configuration changes — expected and
  intentional (a new revision is how Cloud Run applies any config change), not a rebuild.

## Teardown strategy (not executed — documented only)

1. Confirm no DEV data worth keeping (DEV is disposable, synthetic data by design).
2. `terraform apply -var="enable_cloud_sql=false"` — this would attempt to destroy the instance, but
   `deletion_protection = true` blocks it.
3. Explicitly flip `deletion_protection = false` in a dedicated, separately authorized apply.
4. Re-run `terraform apply -var="enable_cloud_sql=false"` — now permitted to destroy.
5. Manually delete the two Secret Manager secrets and their versions (Terraform's `secrets` module
   only ever manages the container, so a `terraform apply -var="enable_secrets=false"` removes the
   containers; the underlying **values** were never Terraform's to manage either way).

`deletion_protection = true` stays the default for as long as this instance is administered, per the
user's explicit instruction — teardown requires the explicit two-step flip above, never a single
`terraform apply`.
