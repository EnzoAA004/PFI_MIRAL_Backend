output "backend_5xx_alert_id" {
  value = var.enable_alerts ? google_monitoring_alert_policy.backend_5xx[0].name : null
}

output "frontend_5xx_alert_id" {
  value = var.enable_alerts ? google_monitoring_alert_policy.frontend_5xx[0].name : null
}

output "backend_uptime_check_id" {
  value = var.enable_uptime_checks ? google_monitoring_uptime_check_config.backend_health[0].uptime_check_id : null
}

output "backend_uptime_alert_id" {
  value = (var.enable_alerts && var.enable_uptime_checks) ? google_monitoring_alert_policy.backend_uptime_failure[0].name : null
}

output "notification_channel_id" {
  value = length(local.notification_channels) > 0 ? local.notification_channels[0] : null
}
