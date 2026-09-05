# enabled = false in every environment shipped by CLOUD-01. This resource
# must never appear as a planned "+ create" until CLOUD-05 explicitly
# authorizes the cost and enables sqladmin.googleapis.com.

resource "google_sql_database_instance" "this" {
  count = var.enabled ? 1 : 0

  project             = var.project_id
  name                = var.instance_name
  region              = var.region
  database_version    = var.database_version
  deletion_protection = var.deletion_protection

  settings {
    tier              = var.tier
    disk_type         = var.disk_type
    disk_size         = var.disk_size_gb
    disk_autoresize   = var.disk_autoresize
    availability_type = var.availability_type

    backup_configuration {
      enabled                        = var.backup_enabled
      point_in_time_recovery_enabled = false # CLOUD-05: PITR not justified for DEV — see CLOUD_05_CLOUD_SQL_DESIGN.md
    }

    ip_configuration {
      # CLOUD-05 networking decision: public IP + Cloud SQL Auth Proxy
      # sidecar in the same Cloud Run v2 service, authenticated via the
      # Backend runtime service account's IAM identity (roles/cloudsql.client)
      # -- never a raw TCP connection authorized by IP. No
      # `authorized_networks` block is declared, and none is exposed as a
      # module variable: the Auth Proxy's tunnel does not go through Cloud
      # SQL's IP allowlist at all, so there is structurally no way for this
      # module to end up whitelisting an IP (0.0.0.0/0 or otherwise) — see
      # CLOUD_05_CLOUD_SQL_DESIGN.md "Network strategy" for why a VPC/
      # private-IP connector was not chosen instead.
      ipv4_enabled = var.ipv4_enabled
    }
  }
}

# The application database. Empty at creation — CLOUD-05 does not migrate
# any data (DEV is currently in-memory, nothing persistent to carry over);
# the Backend's own SqlMigrationRunner builds the schema on first connect,
# exactly as validated locally against a clean Postgres in the CLOUD-05 audit.
resource "google_sql_database" "app" {
  count = var.enabled ? 1 : 0

  project  = var.project_id
  instance = google_sql_database_instance.this[0].name
  name     = var.database_name
}

# Deliberately NO google_sql_user resource here. A Postgres user's password
# is a required, non-optional argument of that resource -- Terraform would
# have to know the plaintext value to send it to the API, and it would then
# sit in this module's state file exactly as plaintext, "random_password"
# generated or not (marking an attribute `sensitive` only redacts CLI output,
# it does not encrypt or omit the value from the raw state). The instruction
# is explicit: no secret value may end up persisted in tfstate.
#
# The user (and its password) are created out-of-band, once, by the human
# operator authorizing this stage -- documented verbatim in
# CLOUD_05_SECRET_MANAGER.md -- and the resulting password is stored only in
# Secret Manager (via `gcloud secrets versions add`, never `terraform apply`).
# This is the standard, accepted tradeoff for this exact class of problem:
# Terraform manages the instance and the database, not the credential.
