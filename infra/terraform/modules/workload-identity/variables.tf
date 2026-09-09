variable "project_id" {
  type = string
}

variable "wif_pool_id" {
  type    = string
  default = "github-pool"
}

variable "wif_provider_id" {
  type    = string
  default = "github-provider"
}

variable "service_account_id" {
  type        = string
  description = "Account ID (local part) of the dedicated push identity, e.g. pfi-github-artifact-pusher."
  default     = "pfi-github-artifact-pusher"
}

variable "service_account_display_name" {
  type    = string
  default = "PFI GitHub Artifact Pusher"
}

variable "github_repositories" {
  type        = list(string)
  description = <<-EOT
    Explicit allowlist of "owner/repo" strings permitted to impersonate the
    push identity via WIF. Deliberately NOT attribute.repository_owner ==
    owner -- every repo must be named explicitly.
  EOT
  default = [
    "EnzoAA004/PFI_MVPTest_Enzo_Backend",
    "EnzoAA004/PFI_MVPTest_Enzo_Frontend",
    "EnzoAA004/PFI_MVPTest_Enzo_AImodule",
  ]
}
