variable "project_id" {
  type    = string
  default = "pfi-asplanatti-fabrello-v1"
}

variable "region" {
  type    = string
  default = "us-central1"
}

variable "environment" {
  type    = string
  default = "demo"
}

variable "backend_image" {
  type        = string
  description = "Must be the exact same image digest already validated in DEV — no rebuild between DEV and DEMO (CLOUD-00O)."
}

variable "frontend_image" {
  type = string
}

variable "ai_image" {
  type = string
}

variable "enable_cloud_sql" {
  type    = bool
  default = false
}

variable "enable_monitoring" {
  type    = bool
  default = false
}
