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

variable "cpu_idle" {
  type        = bool
  description = <<-EOT
    Whether CPU is throttled to (near) zero outside of request handling.
    true = CPU only allocated while actively handling a request (cheaper,
    the standard choice for a bursty/scale-to-zero service). false = CPU
    "always allocated"/unthrottled even while idle -- required by Cloud
    Run's own admission control whenever memory is below 512Mi (CLOUD-04:
    confirmed the hard way -- a service under that floor with cpu_idle=false
    is rejected at apply time with "Total memory < 512 Mi is not supported
    with cpu always allocated"). Defaults to false to keep every existing
    service's behavior byte-identical to before this variable existed
    (Backend already runs at 512Mi+ and was never affected either way).
  EOT
  default     = false
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
  type        = bool
  description = <<-EOT
    true/false emits the run.googleapis.com/startup-cpu-boost annotation
    with that value; null (default) omits the annotation entirely -- the
    conservative default, matching what a freshly-created service actually
    has (CLOUD-04: confirmed the live, already-created Backend service
    carries no such annotation at all, regardless of what earlier config
    intended). Set explicitly per environment/service only when the
    live resource is confirmed to already have that literal value, so
    Terraform's declared state and GCP's actual state stay in sync instead
    of drifting.
  EOT
  default     = null
  nullable    = true
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
