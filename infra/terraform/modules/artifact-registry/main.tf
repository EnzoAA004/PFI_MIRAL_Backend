# EXISTING_EXTERNAL by default: pfi-containers was created manually
# (2026-08-10, confirmed via `gcloud artifacts repositories describe`) and
# CLOUD-01 does not assume it should become Terraform-managed automatically.
#
# To import it later, once the team deliberately decides to manage it:
#   terraform import module.artifact_registry.google_artifact_registry_repository.this[0] \
#     projects/<project_id>/locations/<region>/repositories/<repository_id>
# then flip create_repository = true so the resource block matches reality
# instead of proposing a duplicate create.

resource "google_artifact_registry_repository" "this" {
  count = var.create_repository ? 1 : 0

  project       = var.project_id
  location      = var.region
  repository_id = var.repository_id
  format        = var.format
  description   = "Managed by Terraform (CLOUD-01)."
}

data "google_artifact_registry_repository" "existing" {
  count = var.create_repository ? 0 : 1

  project       = var.project_id
  location      = var.region
  repository_id = var.repository_id
}
