# One shared GitHub -> GCP federation for the whole project. WIF represents
# the trust relationship between GitHub and GCP, not a per-environment
# concept -- DEV and DEMO both reuse this single pool/provider/identity;
# they never get their own copy. See CLOUD_03_ARTIFACT_REGISTRY_WIF.md.
#
# Requires iam.googleapis.com, iamcredentials.googleapis.com and
# sts.googleapis.com -- none enabled on the audited project as of CLOUD-03.
# Writing these resources does not enable those APIs; applying them would
# fail until the APIs are enabled as their own explicit, authorized step.

resource "google_service_account" "artifact_pusher" {
  project      = var.project_id
  account_id   = var.service_account_id
  display_name = var.service_account_display_name
}

resource "google_iam_workload_identity_pool" "github" {
  project                   = var.project_id
  workload_identity_pool_id = var.wif_pool_id
  display_name              = "GitHub Actions"
  description               = "Federates GitHub Actions OIDC tokens for CI/CD image publishing. No service-account JSON key is ever used."
}

resource "google_iam_workload_identity_pool_provider" "github" {
  project                            = var.project_id
  workload_identity_pool_id          = google_iam_workload_identity_pool.github.workload_identity_pool_id
  workload_identity_pool_provider_id = var.wif_provider_id
  display_name                       = "GitHub OIDC"

  attribute_mapping = {
    "google.subject"       = "assertion.sub"
    "attribute.repository" = "assertion.repository"
    "attribute.ref"        = "assertion.ref"
    "attribute.actor"      = "assertion.actor"
  }

  # Explicit per-repository allowlist -- deliberately NOT
  # "assertion.repository_owner == 'EnzoAA004'", which would trust every
  # repo under that owner. Only the three project repos, named explicitly.
  attribute_condition = "assertion.repository in ${jsonencode(var.github_repositories)}"

  oidc {
    issuer_uri = "https://token.actions.githubusercontent.com"
  }
}

# One principalSet binding per allowlisted repo, all pointing at the single
# shared push identity above.
resource "google_service_account_iam_member" "wif_impersonation" {
  for_each = toset(var.github_repositories)

  service_account_id = google_service_account.artifact_pusher.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${google_iam_workload_identity_pool.github.name}/attribute.repository/${each.value}"
}
