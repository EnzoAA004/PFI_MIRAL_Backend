# CLOUD-05 — Change Plan

Both plans below were run against the real `environments/dev` local Terraform state (not a fresh/
empty state), via the established `hashicorp/terraform:1.9` Docker image with
`GOOGLE_OAUTH_ACCESS_TOKEN` from `gcloud auth print-access-token`. `terraform fmt -recursive -diff`
and `terraform validate` both passed cleanly before either plan was run.

## Plan 1 — `enable_cloud_sql=false` (default, current live state)

Run with all other flags at their defaults (`enable_secrets` also defaults `false`).

```
No changes. Your infrastructure matches the configuration.

Terraform has compared your real infrastructure against your configuration
and found no differences, so no changes are needed.
```

**Result: 0 to add, 0 to change, 0 to destroy.** Confirms the CLOUD-05 code changes (new locals, new
IAM resource, new module wiring, the `cloudsql_sidecar`/`secret_env_vars` module variables, the
container `name`/`depends_on` conditionals) are fully inert while both flags stay at their default
`false` — nothing about Frontend, AI, or Backend's live configuration is touched by merely having this
code present.

**Note on one bug found and fixed during this check**: an earlier version of the
`cloud-run-service` module's `containers` block set `name = "app"` unconditionally (needed for the
sidecar's `depends_on` to resolve). Plan 1 initially showed `+ name = "app"` as a spurious in-place
update on **both** the Backend and Frontend live services, because neither was originally created
with an explicit container name. Fixed by making the name conditional on `cloudsql_sidecar != null`
(the same nullable-attribute pattern CLOUD-04 used for `startup_cpu_boost`) — re-running Plan 1
afterward produced the clean "No changes" result above.

## Plan 2 — `enable_cloud_sql=true`, `enable_secrets=true` (the actual CLOUD-05 pre-apply plan)

Full raw output saved at `CLOUD_05_TERRAFORM_PLAN_OUTPUT.txt`. Summary:

**Plan: 7 to add, 1 to change, 0 to destroy.**

| Action | Resource |
|---|---|
| + create | `module.cloud_sql.google_sql_database_instance.this[0]` (`pfi-postgres-dev`, `db-custom-1-3840`, `POSTGRES_15`, ZONAL, 10GB PD_SSD, backups off, public IP + empty authorized_networks) |
| + create | `module.cloud_sql.google_sql_database.app[0]` (`pfi` database) |
| + create | `module.secrets.google_secret_manager_secret.this["pfi-dev-database-url"]` (container only) |
| + create | `module.secrets.google_secret_manager_secret.this["pfi-dev-jwt-secret"]` (container only) |
| + create | `module.iam.google_project_iam_member.bindings["backend_runtime:roles/cloudsql.client"]` |
| + create | `google_secret_manager_secret_iam_member.backend_secret_access["pfi-dev-database-url"]` |
| + create | `google_secret_manager_secret_iam_member.backend_secret_access["pfi-dev-jwt-secret"]` |
| ~ update in-place | `module.backend_cloud_run.google_cloud_run_v2_service.this` |

### Backend Cloud Run update detail (the "1 to change")

- `containers[0]` (the `app` container, now named since a sidecar exists): `depends_on` gains
  `["cloudsql-proxy"]`; `name` becomes `"app"`; the `PFI_PERSISTENCE_MODE=memory` env var is replaced
  with `PFI_PERSISTENCE_MODE=postgres`; two new `secret_key_ref`-sourced env vars appear
  (`DATABASE_URL` → `pfi-dev-database-url:latest`, `PFI_AUTH_JWT_SECRET` → `pfi-dev-jwt-secret:latest`).
- A **new second container** (`cloudsql-proxy`, image
  `gcr.io/cloud-sql-connectors/cloud-sql-proxy:2.14.0`) is added to the same Cloud Run v2 service,
  with its own TCP-socket startup probe on port 5432.
- **`image` is unchanged** — no `~ image = ...` line appears anywhere in the Backend container diff.
  Confirms the same immutable digest (`backend:dee0826c9e1121e348192058acde841b6f933608`) is reused;
  only runtime configuration changes, producing a new Cloud Run *revision* (expected — any config
  change creates a new revision) without any rebuild.

### Confirmed absent from this plan (matches the CLOUD-05 scope constraint)

No Frontend change, no new AI service, no Artifact Registry change, no WIF change, no bucket
replacement or new bucket, no new Artifact Registry repository, no GPU-related resource, no
monitoring resource, no DEMO environment change, no DNS resource. Verified directly in the plan
output: `module.frontend_cloud_run`, `module.artifact_registry`, `module.storage`, and
`module.monitoring` all show zero actions.

### Outputs

`database_connection_name` becomes known (currently `null` since the instance doesn't exist).

## Git status at the time these plans were run

Branch `cloud/cloud-05-managed-persistence` (created from CLOUD-04's `8ac7c0f` HEAD). Modified files:
`infra/terraform/environments/dev/main.tf`, `infra/terraform/environments/dev/variables.tf`,
`infra/terraform/modules/cloud-run-service/main.tf`,
`infra/terraform/modules/cloud-run-service/variables.tf`, `infra/terraform/modules/cloud-sql/main.tf`,
`infra/terraform/modules/cloud-sql/outputs.tf`, `infra/terraform/modules/cloud-sql/variables.tf` — all
Terraform-only changes, no application code touched. No commit made yet for this stage at plan time.
