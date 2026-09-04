variable "project_id" {
  type    = string
  default = "pfi-asplanatti-fabrello-v1"
}

variable "region" {
  type        = string
  description = "Single primary region for all resources, matching where existing GCP resources already live (Artifact Registry, Cloud Run, Storage bucket all confirmed us-central1 in the CLOUD-00 audit) — minimizes latency and egress by not spreading services across regions."
  default     = "us-central1"
}

variable "environment" {
  type    = string
  default = "dev"
}

variable "github_repository" {
  type        = string
  description = "owner/repo allowed to assume the CI/CD deploy identity via WIF."
  default     = "EnzoAA004/PFI_MVPTest_Enzo_Backend"
}

variable "backend_image" {
  type        = string
  description = "Full Artifact Registry image reference, tagged by git SHA. Never :latest."
}

variable "frontend_image" {
  type = string
}

variable "ai_image" {
  type = string
}

variable "enable_cloud_sql" {
  type        = bool
  description = "Must stay false until CLOUD-05 explicitly authorizes the cost and enables sqladmin.googleapis.com."
  default     = false
}

variable "enable_monitoring" {
  type        = bool
  description = "Must stay false until CLOUD-07."
  default     = false
}
