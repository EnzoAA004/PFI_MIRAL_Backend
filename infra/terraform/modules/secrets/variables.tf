variable "project_id" {
  type = string
}

variable "enabled" {
  type        = bool
  description = "false by default (CLOUD-01 requirement). Secret Manager API (secretmanager.googleapis.com) is not enabled on the audited project — flipping this on requires enabling that API first, as its own explicit, separately authorized step."
  default     = false
}

variable "secret_names" {
  type        = list(string)
  description = "Secret container names only — e.g. jwt-secret, db-password, internal-ai-token. Never a real secret VALUE; Terraform never writes a secret version."
  default     = []
}
