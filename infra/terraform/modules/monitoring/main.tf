# CLOUD-07: all resources here stay at zero count until their governing
# flag is explicitly turned on — see CLOUD_07_ALERTING.md and
# CLOUD_07_OBSERVABILITY.md for the reasoning behind each signal/threshold,
# and CLOUD_07_COST_CHECK.md for why log-based custom metrics and a real
# dashboard resource are deliberately NOT modeled here yet (built-in Cloud
# Run metrics already cover CLOUD-07's signals; a custom metric would be
# the first thing in this whole project's Monitoring footprint with an
# ingestion cost, and none is justified yet).

locals {
  notification_channels = (
    var.enable_notification_channels && var.notification_channel_email != "" ?
    [google_monitoring_notification_channel.email[0].id] : []
  )
}

resource "google_monitoring_notification_channel" "email" {
  count = var.enable_notification_channels && var.notification_channel_email != "" ? 1 : 0

  project      = var.project_id
  type         = "email"
  display_name = "PFI Cloud Alerts"

  labels = {
    email_address = var.notification_channel_email
  }
}

# Signal A (CLOUD_07_ALERTING.md): Backend 5xx rate elevated.
resource "google_monitoring_alert_policy" "backend_5xx" {
  count = var.enable_alerts ? 1 : 0

  project      = var.project_id
  display_name = "${var.backend_service_name}: 5xx rate elevated"
  combiner     = "OR"

  conditions {
    display_name = "5xx rate above threshold"

    condition_threshold {
      filter          = "metric.type=\"run.googleapis.com/request_count\" AND resource.type=\"cloud_run_revision\" AND resource.label.service_name=\"${var.backend_service_name}\" AND metric.label.response_code_class=\"5xx\""
      comparison      = "COMPARISON_GT"
      threshold_value = 5
      duration        = "300s"

      aggregations {
        alignment_period   = "300s"
        per_series_aligner = "ALIGN_RATE"
      }
    }
  }

  notification_channels = local.notification_channels
}

# Signal B: Frontend 5xx rate elevated. Same shape as Signal A, different
# service — nginx serving static assets legitimately has a much lower
# expected 5xx baseline, but the same threshold/window is used for now
# rather than inventing an unvalidated separate number; revisit once real
# traffic data exists.
resource "google_monitoring_alert_policy" "frontend_5xx" {
  count = var.enable_alerts ? 1 : 0

  project      = var.project_id
  display_name = "${var.frontend_service_name}: 5xx rate elevated"
  combiner     = "OR"

  conditions {
    display_name = "5xx rate above threshold"

    condition_threshold {
      filter          = "metric.type=\"run.googleapis.com/request_count\" AND resource.type=\"cloud_run_revision\" AND resource.label.service_name=\"${var.frontend_service_name}\" AND metric.label.response_code_class=\"5xx\""
      comparison      = "COMPARISON_GT"
      threshold_value = 5
      duration        = "300s"

      aggregations {
        alignment_period   = "300s"
        per_series_aligner = "ALIGN_RATE"
      }
    }
  }

  notification_channels = local.notification_channels
}

# Signal D: Backend health unavailable — covers total outage, crash-loop,
# and startup failure alike (an uptime check simply gets no 200, regardless
# of *why* the container isn't serving), which is more robust than trying
# to alert on a specific "container crashed" metric. Targets the real
# public liveness path (CLOUD-04: /actuator/health does not exist).
resource "google_monitoring_uptime_check_config" "backend_health" {
  count = var.enable_uptime_checks ? 1 : 0

  project      = var.project_id
  display_name = "${var.backend_service_name}: health check"
  timeout      = "10s"
  period       = "300s"

  http_check {
    path         = var.backend_health_path
    port         = 443
    use_ssl      = true
    validate_ssl = true
  }

  monitored_resource {
    type = "uptime_url"
    labels = {
      project_id = var.project_id
      host       = trimprefix(trimprefix(var.backend_url, "https://"), "http://")
    }
  }
}

resource "google_monitoring_alert_policy" "backend_uptime_failure" {
  count = var.enable_alerts && var.enable_uptime_checks ? 1 : 0

  project      = var.project_id
  display_name = "${var.backend_service_name}: uptime check failing"
  combiner     = "OR"

  conditions {
    display_name = "Uptime check failed"

    condition_threshold {
      filter          = "metric.type=\"monitoring.googleapis.com/uptime_check/check_passed\" AND resource.type=\"uptime_url\" AND metric.label.check_id=\"${google_monitoring_uptime_check_config.backend_health[0].uptime_check_id}\""
      comparison      = "COMPARISON_LT"
      threshold_value = 1
      duration        = "300s"

      aggregations {
        alignment_period     = "300s"
        per_series_aligner   = "ALIGN_FRACTION_TRUE"
        cross_series_reducer = "REDUCE_COUNT_FALSE"
      }
    }
  }

  notification_channels = local.notification_channels
}
