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
