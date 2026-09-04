output "alert_policy_id" {
  value = var.enabled ? google_monitoring_alert_policy.high_5xx[0].name : null
}
