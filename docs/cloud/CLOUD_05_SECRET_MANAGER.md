# CLOUD-05 — Secret Manager Design

## Current state (verified, not assumed)

`secretmanager.googleapis.com` is **disabled** on the project — `gcloud secrets list` fails with an
API-disabled error. Zero secrets exist today. This is the confirmed CLOUD-05 audit finding, not a
bug: the user's explicit instruction for this pass is not to enable the API yet.

## What Terraform creates — containers only, never a value

`modules/secrets` declares exactly one resource type:

```hcl
resource "google_secret_manager_secret" "this" {
  for_each = var.enabled ? toset(var.secret_names) : toset([])
  project   = var.project_id
  secret_id = each.value
  replication { auto {} }
}
```

There is, and has never been, a `google_secret_manager_secret_version` resource in this module. A
secret **container** (name + replication policy metadata) carries no confidential data — only the
version resource would, and it does not exist here. This means no secret value can reach
`terraform.tfstate` through this module, by construction, not by convention.

## Two secrets only

The Backend has exactly one persistence-config env var (`DATABASE_URL` — no separate
`PFI_DB_USER`/`PFI_DB_PASSWORD` variables exist in the app) and one auth secret
(`PFI_AUTH_JWT_SECRET`, currently defaulting insecurely to `pfi-demo-change-me-2026` per the CLOUD-00
audit finding). CLOUD-05 creates:

| Secret ID | Backend env var | Value (created out-of-band, never by Terraform) |
|---|---|---|
| `pfi-dev-database-url` | `DATABASE_URL` | Full JDBC URL **including embedded credentials**: `jdbc:postgresql://127.0.0.1:5432/pfi?sslmode=disable&user=<user>&password=<password>` (TLS is unnecessary over the proxy's local-loopback tunnel — the proxy itself encrypts the connection to Cloud SQL) |
| `pfi-dev-jwt-secret` | `PFI_AUTH_JWT_SECRET` | A freshly generated, sufficiently long random string — replaces the insecure `pfi-demo-change-me-2026` default for this DEV cloud deployment |

This eliminates the CLOUD-00-flagged insecure JWT default from the DEV cloud deployment: once
`enable_secrets = true` and the Backend consumes `PFI_AUTH_JWT_SECRET` from Secret Manager, the
image's insecure built-in default is never reached for this environment.

## Out-of-band procedure (manual, run once per environment, never committed, never in Terraform)

These commands are **not executed by this stage** — they are the documented procedure for the future,
separately authorized apply/activation step.

```bash
# 1. Enable the API (separately authorized step, not done in this pass)
gcloud services enable secretmanager.googleapis.com --project=<project-id>

# 2. Create the DB user (also out-of-band — see CLOUD_05_CLOUD_SQL_DESIGN.md "Credential strategy")
gcloud sql users create pfi_backend \
  --instance=pfi-postgres-dev \
  --password="$(openssl rand -base64 24)"

# 3. Generate and store the JWT secret value (never echoed to a log or committed)
openssl rand -base64 48 | tr -d '\n' | gcloud secrets versions add pfi-dev-jwt-secret --data-file=-

# 4. Build and store the DATABASE_URL value (password never appears in shell history if piped from
#    a variable set via `read -s`, not typed as a literal argument)
printf 'jdbc:postgresql://127.0.0.1:5432/pfi?sslmode=disable&user=pfi_backend&password=%s' \
  "$DB_PASSWORD" | gcloud secrets versions add pfi-dev-database-url --data-file=-
```

No secret value is ever passed as a `-var`/`-var-file` to Terraform, ever appears in a `terraform
plan`/`apply` output, and is never printed by any command in this repo's automation.

## IAM — resource-scoped, not project-wide

`google_secret_manager_secret_iam_member.backend_secret_access` (in
`infra/terraform/environments/dev/main.tf`) grants `roles/secretmanager.secretAccessor` **per secret
ID**, only to `pfi-backend-runtime`, only for the two secrets the Backend actually reads — never a
project-wide `secretmanager.admin`/`secretAccessor` binding, and never granted to the Frontend or AI
runtime service accounts (neither is referenced anywhere in this binding). Gated by `enable_secrets`
so no IAM binding is created while the underlying secret containers don't exist either.

## Verification plan (for the later, authorized activation step — not run yet)

1. `gcloud run services describe pfi-backend-dev --format=json | jq '.spec.template.spec.containers[0].env'`
   — confirm the env entries show a `secretKeyRef`, never a literal value.
2. `git log -p -- infra/terraform/environments/dev` — confirm no secret value ever appears in any
   commit.
3. `terraform plan`/`apply` output — confirm neither command ever prints a secret value (only
   `(sensitive value)`/`(known after apply)` placeholders for anything secret-adjacent).
4. Application logs — confirm no log line ever contains a raw `DATABASE_URL` or JWT secret value
   (the Backend's existing logging was not audited for this in CLOUD-05; flag as a follow-up check
   during the activation step, not before).
