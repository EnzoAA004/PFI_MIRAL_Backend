resource "google_cloud_run_v2_service" "this" {
  name     = var.name
  project  = var.project_id
  location = var.region
  ingress  = var.ingress
  labels   = var.labels

  template {
    service_account = var.service_account_email

    scaling {
      min_instance_count = var.min_instance_count
      max_instance_count = var.max_instance_count
    }

    max_instance_request_concurrency = var.concurrency
    timeout                          = "${var.timeout_seconds}s"

    # CLOUD-04: null means "don't emit this annotation at all" -- the
    # unconditional tostring(var.startup_cpu_boost) this replaced always sent
    # the annotation (default true), but Cloud Run's live API never echoed it
    # back for the already-created Backend service, so every later plan
    # showed a perpetual "+" drift for a key that was never really absent by
    # choice. Modeling it explicitly (present only when true/false is
    # actually wanted) lets each environment's config match its live state
    # exactly, instead of masking the mismatch with a blanket
    # lifecycle.ignore_changes on the whole annotations map (which would also
    # hide unrelated, legitimate future drift).
    annotations = var.startup_cpu_boost == null ? {} : {
      "run.googleapis.com/startup-cpu-boost" = tostring(var.startup_cpu_boost)
    }

    containers {
      image = var.image

      ports {
        container_port = var.port
      }

      resources {
        limits = {
          cpu    = var.cpu
          memory = var.memory
        }
        cpu_idle = var.cpu_idle
      }

      dynamic "env" {
        for_each = var.env_vars
        content {
          name  = env.value.name
          value = env.value.value
        }
      }

      dynamic "env" {
        for_each = var.secret_env_vars
        content {
          name = env.value.name
          value_source {
            secret_key_ref {
              secret  = env.value.secret_name
              version = env.value.version
            }
          }
        }
      }

      dynamic "startup_probe" {
        for_each = var.health_check_path != null ? [1] : []
        content {
          http_get {
            path = var.health_check_path
          }
          initial_delay_seconds = 0
          period_seconds        = 10
          timeout_seconds       = 5
          failure_threshold     = 3
        }
      }
    }
  }
}

# Public/private policy: only grants invoker to allUsers when explicitly
# requested. The AI service (allow_unauthenticated = false) stays reachable
# only by identities the caller wires up separately (e.g. the Backend's
# runtime service account) — not modeled here to avoid this reusable module
# hardcoding a specific caller identity.
resource "google_cloud_run_v2_service_iam_member" "public" {
  count = var.allow_unauthenticated ? 1 : 0

  project  = var.project_id
  location = var.region
  name     = google_cloud_run_v2_service.this.name
  role     = "roles/run.invoker"
  member   = "allUsers"
}
