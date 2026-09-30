variable "project_id" {
  type = string
}

variable "region" {
  type = string
}

variable "bucket_name" {
  type = string
}

variable "create_bucket" {
  type        = bool
  description = "false (default, CLOUD-01 preference): treat pfi-rm-lumbar-artifacts-2026-ef as EXISTING_EXTERNAL via a data source. true: Terraform creates and owns the bucket."
  default     = false
}

variable "storage_class" {
  type    = string
  default = "STANDARD"
}

variable "uniform_bucket_level_access" {
  type    = bool
  default = true
}

variable "versioning" {
  type    = bool
  default = false
}
