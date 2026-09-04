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

# Workload Identity Federation moved to modules/workload-identity + the
# platform/identity root module (CLOUD-03): WIF represents the GitHub<->GCP
# trust relationship, a project-wide concept, not something each
# environment (dev/demo) should instantiate its own copy of. This module
# stays focused on runtime service accounts and their project-level role
# grants only.
