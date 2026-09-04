variable "project_id" {
  type = string
}

variable "service_accounts" {
  description = <<-EOT
    Map keyed by a logical name (referenced from project_iam_bindings and
    from environment main.tf module outputs). Each entry is either:
      - create = false, email = "<existing-sa-email>"  (EXISTING_EXTERNAL —
        e.g. the pfi-backend-runtime / pfi-ai-runtime accounts already found
        in the CLOUD-00 audit, never modified by this module beyond IAM
        role grants)
      - create = true, account_id = "...", display_name = "..." (a NEW
        least-privilege service account this module creates, e.g. a
        per-environment CI/CD deploy identity or a Frontend runtime
        identity that does not exist yet)
  EOT
  type = map(object({
    create       = bool
    account_id   = optional(string)
    display_name = optional(string)
    email        = optional(string)
  }))
  default = {}
}

variable "project_iam_bindings" {
  description = "Additive project-level role grants. member_key must be a key in var.service_accounts. Never grant roles/owner or roles/editor here."
  type = list(object({
    member_key = string
    role       = string
  }))
  default = []
}

variable "enable_wif" {
  type        = bool
  description = "Writes the Workload Identity Federation pool/provider design as Terraform resources. Still never applied by CLOUD-01 — see CLOUD_SECURITY_MODEL.md. Default false so the base plan stays minimal; environments/dev turns it on deliberately to preview the design."
  default     = false
}

variable "wif_pool_id" {
  type    = string
  default = "github-actions-pool"
}

variable "wif_provider_id" {
  type    = string
  default = "github-actions-provider"
}

variable "github_repository" {
  type        = string
  description = "owner/repo allowed to impersonate the deploy identity, e.g. EnzoAA004/PFI_MVPTest_Enzo_Backend."
  default     = ""
}

variable "deploy_service_account_key" {
  type        = string
  description = "Key into var.service_accounts identifying the CI/CD deploy identity that GitHub Actions impersonates via WIF."
  default     = ""
}
