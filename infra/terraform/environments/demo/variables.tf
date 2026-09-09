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
  description = "Must be the exact same image digest already validated in DEV — no rebuild between DEV and DEMO. CLOUD-06's promotion workflow verifies this digest match before any DEMO plan/apply."
}

variable "frontend_image" {
  type        = string
  description = "Must be the exact same image digest already validated in DEV — no rebuild between DEV and DEMO."
}

variable "ai_image" {
  type = string
}

variable "enable_cloud_sql" {
  type        = bool
  description = "false by default (CLOUD-06). Same Cloud SQL Auth Proxy sidecar design as DEV (CLOUD-05) — a separate instance (pfi-postgres-demo), never shared with DEV. Requires its own cost authorization before ever being set true; CLOUD-06 does not request that authorization."
  default     = false
}

variable "enable_secrets" {
  type        = bool
  description = "false by default (CLOUD-06). Mirrors DEV's CLOUD-05 design: Secret Manager secret CONTAINERS only, values always out-of-band, never a Terraform secret_version."
  default     = false
}

variable "enable_monitoring" {
  type    = bool
  default = false
}

variable "enable_ai_service" {
  type        = bool
  description = "false by default. The pre-existing pfi-ai-module stays untouched; a dedicated DEMO AI Cloud Run service is not created unless explicitly authorized in a later stage."
  default     = false
}

variable "backend_min_instances" {
  type        = number
  description = "0 (default, cost-conscious — matches DEV) avoids idle Cloud Run cost outside of scheduled demo windows, at the price of a cold start on the first request after idle. 1 removes cold starts for a live demo session — see CLOUD_06_COST_CHECK.md for both scenarios' cost. Set explicitly per demo session, not hardcoded."
  default     = 0
}

variable "frontend_min_instances" {
  type        = number
  description = "Same tradeoff as backend_min_instances, sized independently since Frontend's cold start (static nginx) is far cheaper than Backend's (JVM startup)."
  default     = 0
}
