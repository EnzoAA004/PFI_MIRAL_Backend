variable "project_id" {
  type        = string
  description = "GCP project ID."
}

variable "region" {
  type        = string
  description = "Region of the Artifact Registry repository."
}

variable "repository_id" {
  type        = string
  description = "Repository ID, e.g. \"pfi-containers\"."
}

variable "format" {
  type        = string
  description = "Repository format."
  default     = "DOCKER"
}

variable "create_repository" {
  type        = bool
  description = <<-EOT
    false (default, CLOUD-01 preference): treat the repository as
    EXISTING_EXTERNAL, referenced via a data source, never created or
    modified by Terraform.
    true: Terraform creates and owns the repository (future state, once a
    deliberate decision is made to import or replace the existing one).
  EOT
  default     = false
}

variable "writer_members" {
  type        = list(string)
  description = "IAM members (e.g. \"serviceAccount:pfi-github-artifact-pusher@...\") granted roles/artifactregistry.writer on THIS repository only (CLOUD-03). Empty by default -- no one is granted push access until explicitly wired."
  default     = []
}
