# The existing bucket (pfi-rm-lumbar-artifacts-2026-ef, confirmed via
# `gcloud storage buckets list` in the CLOUD-00 audit) is never deleted or
# replaced by this module. Default is EXISTING_EXTERNAL, same pattern as
# the artifact-registry module.

resource "google_storage_bucket" "this" {
  count = var.create_bucket ? 1 : 0

  project                     = var.project_id
  name                        = var.bucket_name
  location                    = var.region
  storage_class               = var.storage_class
  uniform_bucket_level_access = var.uniform_bucket_level_access
  public_access_prevention    = "enforced"

  versioning {
    enabled = var.versioning
  }
}

data "google_storage_bucket" "existing" {
  count = var.create_bucket ? 0 : 1

  name = var.bucket_name
}
