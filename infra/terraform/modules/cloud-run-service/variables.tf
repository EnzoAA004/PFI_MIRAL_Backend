variable "project_id" {
  type = string
}

variable "region" {
  type = string
}

variable "name" {
  type        = string
  description = "Cloud Run service name."
}

variable "image" {
  type        = string
  description = "Full image reference, tagged by git SHA — never :latest."
}

variable "service_account_email" {
  type        = string
  description = "Runtime service account for this service (least privilege — see CLOUD_SECURITY_MODEL.md)."
}

variable "port" {
  type    = number
  default = 8080
}

variable "min_instance_count" {
  type    = number
  default = 0
}

variable "max_instance_count" {
  type    = number
  default = 2
}

variable "cpu" {
  type    = string
  default = "1"
}

variable "memory" {
  type    = string
  default = "512Mi"
}

variable "concurrency" {
  type    = number
  default = 80
}

variable "timeout_seconds" {
  type    = number
  default = 300
}

variable "ingress" {
  type        = string
  description = "One of INGRESS_TRAFFIC_ALL, INGRESS_TRAFFIC_INTERNAL_ONLY, INGRESS_TRAFFIC_INTERNAL_LOAD_BALANCER."
  default     = "INGRESS_TRAFFIC_ALL"
}

variable "allow_unauthenticated" {
  type        = bool
  description = "true grants roles/run.invoker to allUsers (public Frontend/Backend). false keeps the service private (internal AI service)."
  default     = false
}

variable "startup_cpu_boost" {
  type    = bool
  default = true
}

variable "health_check_path" {
  type        = string
  description = "HTTP path for the startup probe, e.g. /actuator/health or /health. null skips the probe."
  default     = null
}

variable "env_vars" {
  type = list(object({
    name  = string
    value = string
  }))
  default = []
}

variable "secret_env_vars" {
  type = list(object({
    name        = string
    secret_name = string
    version     = string
  }))
  description = "Env vars sourced from Secret Manager. Only usable once modules/secrets is enabled."
  default     = []
}

variable "labels" {
  type    = map(string)
  default = {}
}
