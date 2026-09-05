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
      # CLOUD-05: name is only set when a sidecar exists. A live single-
      # container service (Frontend, AI, Backend before CLOUD-05) was created
      # with no explicit container name at all -- confirmed the hard way
      # (CLOUD-05: an earlier unconditional name = "app" here showed as a
      # spurious "+ name = app" in-place update on every existing service).
      # A name is only actually required by the API to make `depends_on`
      # between containers resolvable, i.e. only once a sidecar exists.
      name  = var.cloudsql_sidecar == null ? null : "app"
      image = var.image

      # CLOUD-05: only set when a sidecar exists, so this container's plan
      # stays byte-identical when cloudsql_sidecar is null (Frontend, AI, and
      # Backend before CLOUD-05). Cloud Run waits for the named container's
      # own startup_probe to pass before starting this one — otherwise the
      # app could race the proxy and fail its first connection attempt.
      depends_on = var.cloudsql_sidecar == null ? null : ["cloudsql-proxy"]

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

    # CLOUD-05: the Cloud SQL Auth Proxy as a sidecar in the SAME Cloud Run
    # v2 service — not baked into the app image (no rebuild), and not a
    # separate Cloud Run service. It authenticates to Cloud SQL using this
    # service's own runtime service account (roles/cloudsql.client), and
    # exposes a plain local TCP port the app's existing, unmodified plain
    # PgJDBC driver already knows how to talk to — no
    # cloud-sql-jdbc-socket-factory dependency needed in the app (confirmed
    # absent from pom.xml; adding it would have forced a rebuild).
    dynamic "containers" {
      for_each = var.cloudsql_sidecar == null ? [] : [var.cloudsql_sidecar]
      content {
        name  = "cloudsql-proxy"
        image = containers.value.image

        args = [
          "--structured-logs",
          "--port=${containers.value.port}",
          containers.value.instance_connection_name,
        ]

        startup_probe {
          tcp_socket {
            port = containers.value.port
          }
          initial_delay_seconds = 0
          period_seconds        = 2
          timeout_seconds       = 2
          failure_threshold     = 15
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
