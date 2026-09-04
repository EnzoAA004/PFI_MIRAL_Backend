# enabled = false in every environment shipped by CLOUD-01. Design only —
# corresponds to CLOUD_ROLLBACK_OBSERVABILITY.md's "HTTP 5xx rate" signal.
# Additional policies (latency, AI timeout, DB errors) follow the same
# pattern and are added at CLOUD-07 time, not needed to validate the
# skeleton here.

resource "google_monitoring_alert_policy" "high_5xx" {
  count = var.enabled ? 1 : 0

  project      = var.project_id
  display_name = "Cloud Run 5xx rate"
  combiner     = "OR"

  conditions {
    display_name = "5xx rate above threshold"

    condition_threshold {
      filter          = "metric.type=\"run.googleapis.com/request_count\" AND resource.type=\"cloud_run_revision\" AND metric.label.response_code_class=\"5xx\""
      comparison      = "COMPARISON_GT"
      threshold_value = 5
      duration        = "300s"

      aggregations {
        alignment_period   = "300s"
        per_series_aligner = "ALIGN_RATE"
      }
    }
  }

  notification_channels = var.notification_channels
}
