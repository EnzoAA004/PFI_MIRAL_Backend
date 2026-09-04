variable "project_id" {
  type = string
}

variable "region" {
  type = string
}

variable "instance_name" {
  type = string
}

variable "enabled" {
  type        = bool
  description = "false by default, required by CLOUD-01. sqladmin.googleapis.com is not enabled on the audited project — flipping this on requires enabling that API and accepting Cloud SQL's always-on cost (see CLOUD_COST_MODEL.md), as its own explicit, separately authorized step (CLOUD-05)."
  default     = false
}

variable "database_version" {
  type    = string
  default = "POSTGRES_15"
}

variable "tier" {
  type        = string
  description = "DEV low-cost default: db-f1-micro (shared-core, ~$8/month). DEMO should use a small dedicated tier for reliability — see CLOUD_COST_MODEL.md."
  default     = "db-f1-micro"
}

variable "disk_size_gb" {
  type    = number
  default = 10
}

variable "backup_enabled" {
  type    = bool
  default = true
}

variable "ipv4_enabled" {
  type        = bool
  description = "Public IP. false is the safer default once private networking (VPC connector) is designed; true only as an interim DEV convenience."
  default     = false
}

variable "deletion_protection" {
  type    = bool
  default = true
}
