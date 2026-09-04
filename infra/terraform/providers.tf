# Canonical provider configuration (reference — see versions.tf header).
# Duplicated verbatim into environments/dev and environments/demo.
#
# Authentication is deliberately NOT configured here. No `credentials =
# file("key.json")` block, ever. Local runs use Application Default
# Credentials (`gcloud auth application-default login`); CI/CD runs use
# Workload Identity Federation (see modules/iam and CLOUD_SECURITY_MODEL.md).

provider "google" {
  project = var.project_id
  region  = var.region
}
