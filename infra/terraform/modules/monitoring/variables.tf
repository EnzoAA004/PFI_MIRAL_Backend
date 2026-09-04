variable "project_id" {
  type = string
}

variable "enabled" {
  type        = bool
  description = "false by default, required by CLOUD-01. No alert policy is created until this is deliberately turned on (CLOUD-07)."
  default     = false
}

variable "notification_channels" {
  type    = list(string)
  default = []
}
