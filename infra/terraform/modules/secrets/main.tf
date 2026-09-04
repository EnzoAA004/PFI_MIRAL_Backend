# Only the secret CONTAINER (name + replication policy) is ever declared
# here — never a google_secret_manager_secret_version with a real value.
# Actual secret values are created out-of-band (e.g. `gcloud secrets
# versions add`, run manually, never committed, never in Terraform state).

resource "google_secret_manager_secret" "this" {
  for_each = var.enabled ? toset(var.secret_names) : toset([])

  project   = var.project_id
  secret_id = each.value

  replication {
    auto {}
  }
}
