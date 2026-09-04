resource "google_service_account" "created" {
  for_each = { for k, v in var.service_accounts : k => v if v.create }

  project      = var.project_id
  account_id   = each.value.account_id
  display_name = each.value.display_name
}

locals {
  service_account_emails = {
    for k, v in var.service_accounts :
    k => v.create ? google_service_account.created[k].email : v.email
  }
}

# Additive, non-authoritative bindings only (google_project_iam_member) —
# never google_project_iam_policy / _binding, which would overwrite every
# other existing binding on the project (including bindings this Terraform
# project knows nothing about). Least privilege, explicit roles only —
# never roles/owner or roles/editor.
resource "google_project_iam_member" "bindings" {
  for_each = { for b in var.project_iam_bindings : "${b.member_key}:${b.role}" => b }

  project = var.project_id
  role    = each.value.role
  member  = "serviceAccount:${local.service_account_emails[each.value.member_key]}"
}

# ---------------------------------------------------------------------------
# Workload Identity Federation (design only — see CLOUD_SECURITY_MODEL.md).
# Written as real Terraform resources per the CLOUD-01 instruction, gated
# behind enable_wif (default false). Requires iam.googleapis.com and
# iamcredentials.googleapis.com, neither enabled on the audited project
# today — these resources are proposals for a future `terraform apply`
# under its own explicit authorization, never applied by CLOUD-01.
# ---------------------------------------------------------------------------

resource "google_iam_workload_identity_pool" "github" {
  count = var.enable_wif ? 1 : 0

  project                   = var.project_id
  workload_identity_pool_id = var.wif_pool_id
  display_name              = "GitHub Actions"
  description               = "Federates GitHub Actions OIDC tokens for CI/CD deploys. No service-account JSON key is ever used."
}

resource "google_iam_workload_identity_pool_provider" "github" {
  count = var.enable_wif ? 1 : 0

  project                            = var.project_id
  workload_identity_pool_id          = google_iam_workload_identity_pool.github[0].workload_identity_pool_id
  workload_identity_pool_provider_id = var.wif_provider_id
  display_name                       = "GitHub OIDC"

  attribute_mapping = {
    "google.subject"       = "assertion.sub"
    "attribute.repository" = "assertion.repository"
  }

  # Restrict to this exact repo — never leave this open to "any GitHub repo".
  attribute_condition = "assertion.repository == \"${var.github_repository}\""

  oidc {
    issuer_uri = "https://token.actions.githubusercontent.com"
  }
}

resource "google_service_account_iam_member" "wif_impersonation" {
  count = var.enable_wif && var.deploy_service_account_key != "" ? 1 : 0

  service_account_id = "projects/${var.project_id}/serviceAccounts/${local.service_account_emails[var.deploy_service_account_key]}"
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${google_iam_workload_identity_pool.github[0].name}/attribute.repository/${var.github_repository}"
}
