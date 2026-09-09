variable "project_id" {
  type = string
}

# CLOUD-07: replaces the single CLOUD-01 `enabled` flag with three granular
# flags so a future environment can turn on, say, uptime checks without
# also committing to alert policies (or vice versa). All false by default —
# no monitoring resource is created until deliberately turned on.
variable "enable_alerts" {
  type        = bool
  description = "Creates the alert policies (Backend/Frontend 5xx rate, Backend uptime-check failure). False by default."
  default     = false
}

variable "enable_uptime_checks" {
  type        = bool
  description = "Creates the Backend health uptime check. False by default. enable_alerts also requires this to be true for the uptime-failure alert policy to exist (it has nothing to alert on otherwise)."
  default     = false
}

variable "enable_notification_channels" {
  type        = bool
  description = "Creates a notification channel (currently: email) that alert policies can page. False by default — even with enable_alerts=true, alert policies are created with an empty notification_channels list (they'd fire and be visible in Cloud Monitoring, but notify no one) until this is turned on with a real address."
  default     = false
}

variable "notification_channel_email" {
  type        = string
  description = "Email address for the notification channel. Only used when enable_notification_channels=true; the channel resource itself is not created if this stays empty even then (avoids creating a channel with a placeholder/wrong address)."
  default     = ""
}

variable "backend_service_name" {
  type        = string
  description = "Cloud Run service name the Backend alert policies/uptime check target."
}

variable "backend_url" {
  type        = string
  description = "Backend's full https:// Cloud Run URL — the uptime check needs a bare host, derived from this via trimprefix(). Required only when enable_uptime_checks=true."
  default     = ""
}

variable "frontend_service_name" {
  type        = string
  description = "Cloud Run service name the Frontend alert policy targets."
}

variable "backend_health_path" {
  type        = string
  description = "Path the uptime check requests. Must be the real public liveness endpoint (CLOUD-04: /actuator/health does not exist)."
  default     = "/api/system/health"
}

variable "region" {
  type = string
}
