# CLOUD-05 — Cloud SQL Design

**CORRECTION (CLOUD-05 re-audit, see `CLOUD_05_COST_OPTIMIZATION_REVIEW.md` for the full
investigation): the "Tier correction" section below, and the DEV-min row of the comparison table,
were factually wrong in the original CLOUD-05 pass. `db-f1-micro` IS a currently-orderable,
regular-support tier for PostgreSQL 15 in `us-central1` — the original conclusion came from an
incomplete Billing Catalog SKU search (matched the literal substring "f1-micro", which only appears
in Extended-support-labeled SKUs; current pricing is filed under "Micro instance" instead) combined
with unexhausted pagination (stopped at 15,000 of ~40,500 total SKUs). This is now corrected
throughout this document — `db-f1-micro` is the DEV-min default, not `db-custom-1-3840`.**

## Engine and version

**Cloud SQL for PostgreSQL, `POSTGRES_15`.** No MySQL, no engine change — the application already
speaks plain PgJDBC (`org.postgresql:postgresql:42.7.4`). `POSTGRES_15` is a current, standard-support
major version — confirmed via the Cloud Billing Catalog API that it is **not** subject to the
"Extended support" pricing surcharge that now applies to PostgreSQL 9.6–13.

## Tier — corrected finding

`db-f1-micro` (shared-core, `edition=ENTERPRISE`) **is a currently-orderable tier for a new
PostgreSQL 15 instance in `us-central1`** — confirmed two ways: the live Cloud SQL Admin API's
`tiers.list` endpoint (a static reference listing that does not require `sqladmin.googleapis.com`
enabled) returns it with `edition: "ENTERPRISE"` and `us-central1` in its region list; and the fully
paged Billing Catalog SKU list (all ~40,500 SKUs for the Cloud SQL service, not the 15,000 the
original pass stopped at) contains a live, current, non-extended-support SKU:
`Cloud SQL for PostgreSQL: Zonal - Micro instance in Americas` (`C2D4-F7DF-B8D0`) at
**$0.0105/hour**. See `CLOUD_05_COST_OPTIMIZATION_REVIEW.md` for the full re-audit, including exactly
how the original conclusion went wrong. `db-custom-*` dedicated machine types remain available and
are kept as this module's alternative for a future DEMO/prod-like environment that needs a real SLA
or more than ~614 MiB RAM.

## Sourced pricing (Cloud Billing Catalog API, `us-central1`/Iowa, captured 2026-09-04)

| Resource | SKU | Rate |
|---|---|---|
| Zonal shared-core (`db-f1-micro`) | `C2D4-F7DF-B8D0` "Cloud SQL for PostgreSQL: Zonal - Micro instance in Americas" | $0.0105 / instance-hour (bundled, ~614 MiB RAM included) |
| Zonal vCPU (dedicated/custom tiers) | `965E-7C36-08E6` "Cloud SQL for PostgreSQL: Zonal - vCPU in Americas" | $0.0413 / vCPU-hour |
| Zonal RAM (dedicated/custom tiers) | `93DA-3F55-CB04` "Cloud SQL for PostgreSQL: Zonal - RAM in Americas" | $0.0070 / GiB-hour |
| Zonal SSD storage | `903C-B42D-4287` "Zonal - Enterprise Plus Standard Storage" (matches the plain "Standard storage" Postgres SKU at the same $0.17 rate seen on the SQL Server and FDC-trial equivalents) | $0.17 / GiB-month |
| Backups | `46C8-DA82-DF18` "Cloud SQL: Backups in Americas" | $0.08 / GiB-month |
| Regional (HA) vCPU/RAM | `1912-86A6-9950` / `9BE2-CB5B-66F8` | exactly 2× the Zonal dedicated rate ($0.0826/vCPU-hr, $0.0140/GiB-hr) |

Regional/HA being exactly double Zonal at every dedicated-tier resource is consistent, not a rounding
coincidence — confirms HA in Cloud SQL is priced as a literal second (standby) instance. Shared-core
tiers (`db-f1-micro`/`db-g1-small`) do not support Regional/HA at all.

## Tier comparison

| Option | Config | RAM | Disk | Backup | HA | 24h cost | 7-day cost | 30-day (730h) cost | Stoppable? | Stopped-instance cost | Recommended |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **DEV-min** | `db-f1-micro` (shared-core, ENTERPRISE) | ~614 MiB (fixed) | 10 GB PD_SSD | off | ZONAL only | ~$0.31 | ~$2.16 | **~$9.37** | Yes (`activation_policy=NEVER`) | ~$1.70/mo (storage only) | **Yes — this stage's corrected default** |
| **DEMO** | `db-f1-micro` | ~614 MiB (fixed) | 10 GB PD_SSD | **on** | ZONAL only | ~$0.33 | ~$2.36 | ~$10.17 | Yes | ~$1.70/mo | Flip `backup_enabled=true` only when a demo is scheduled |
| **Dedicated DEV alternative** | `db-custom-1-3840` | 3.75 GiB | 10 GB PD_SSD | off | ZONAL | ~$1.68 | ~$11.75 | ~$51.01 | Yes | ~$1.70/mo | Only if `db-f1-micro`'s ~614 MiB RAM proves insufficient in practice |
| **Future prod-grade** | `db-custom-2-7680` | 7.5 GiB | 20 GB PD_SSD | on | **REGIONAL (HA)** | ~$6.75 | ~$47.27 | ~$205.65 | No (HA defeats the point of stopping) | n/a | Not proposed now — needs its own authorization if the project ever needs real uptime SLAs |

DEV-min math (730h/month): `$0.0105/h × 730h = $7.665` compute + `10 × $0.17 = $1.70` storage =
**$9.365/month ≈ $9.37/month**, or **~$0.31/day**. Backups off by default add nothing; turning them
on for a DEMO adds `10 × $0.08 = $0.80/month`.

**This design proposes DEV-min (`db-f1-micro`) only** — an 82% reduction versus the previously
reviewed `db-custom-1-3840` figure (~$51.01/month). The dedicated and future-prod rows remain
available as documented fallbacks, not requested here.

## Known limitations of `db-f1-micro` (documented, accepted for DEV)

- **~614 MiB RAM, fixed** — not configurable independently of the tier. Sufficient for a thesis
  DEV/smoke-test database (schema + a handful of synthetic rows); would need re-evaluation if DEV
  data volume or concurrent connection count grows meaningfully.
- **No SLA coverage** — Google's own documentation states shared-core tiers (`db-f1-micro`,
  `db-g1-small`) are not covered by the Cloud SQL SLA and are not recommended for production use.
  Acceptable for DEV; not acceptable for DEMO if the demo itself needs guaranteed uptime (a DEMO
  outage risk vs. a ~$41/month savings is a judgment call for whoever schedules the demo, not decided
  here).
- **No Regional/HA support** — matches this design's ZONAL-only choice anyway, so not a new
  constraint in practice.
- **Migration compatibility unaffected** — the CLOUD-05 migration validation (17 migrations against a
  clean local Postgres) tested schema/query correctness, not tier-specific resource limits; nothing
  about `db-f1-micro`'s smaller RAM changes SQL compatibility, only headroom under load.

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
