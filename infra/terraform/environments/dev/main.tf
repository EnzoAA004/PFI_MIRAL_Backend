terraform {
  required_version = ">= 1.9.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 8.1"
    }
  }

  # Local backend for CLOUD-01 — no GCS state bucket exists yet. See
  # docs/cloud/CLOUD_01_TERRAFORM_FOUNDATION.md "Remote state" section for
  # the planned future migration (bucket name, prefix, locking).
  backend "local" {}
}

provider "google" {
  project = var.project_id
  region  = var.region
}

# CLOUD-04: the Backend's CORS allowlist needs the Frontend's real URL, but
# Backend cannot reference module.frontend_cloud_run.url directly -- Frontend
# already depends on module.backend_cloud_run.url for BACKEND_URL, and
# Terraform's module graph must stay acyclic (a direct reference the other
# way is a hard "Error: Cycle", not a style choice).
#
# CLOUD-04 first attempt used a *predicted* URL
# (https://<service>-<project-number>.<region>.run.app) reasoned from the
# pre-existing pfi-ai-module service's own alternate URL. That format is a
# real, valid alias, but it is NOT the one google_cloud_run_v2_service.uri
# actually returns (confirmed empirically: Terraform's own `frontend_url`
# output, and the browser, both used the
# https://<service>-<hash>-<region-code>.a.run.app form instead) -- a real
# CORS failure resulted (documented in CLOUD_04_CLOUD_RUN_DEV.md).
#
# Fixed by reading the Frontend's live, already-created resource directly
# via a name-based data source -- genuinely "the real URL", not predicted --
# while still avoiding the module-to-module cycle (this is a fresh lookup by
# name, not a reference into module.frontend_cloud_run's own attributes).
#
# Bootstrapping note: this requires the Frontend service to already exist,
# which is true from this point in CLOUD-04 onward. A from-scratch
# `terraform apply` of a fully-destroyed environment would need Backend and
# Frontend applied in two passes (Frontend first, or Backend first without
# this origin and a follow-up CORS update like this one) -- the same
# real ordering constraint CLOUD-04 already hit once, now made explicit
# rather than hidden behind a prediction that turned out wrong.
data "google_cloud_run_v2_service" "frontend_lookup" {
  name     = "pfi-frontend-${var.environment}"
  location = var.region
  project  = var.project_id
}

module "artifact_registry" {
  source = "../../modules/artifact-registry"

  project_id        = var.project_id
  region            = var.region
  repository_id     = "pfi-containers"
  create_repository = false # EXISTING_EXTERNAL

  # No writer_members here -- CLOUD-03: the repository-scoped writer grant
  # for the shared GitHub push identity is owned exclusively by
  # infra/terraform/platform/identity, applied once, not per environment.
  # This module instance exists only to expose repository_url for image
  # tag composition below.
}

module "storage" {
  source = "../../modules/storage"

  project_id    = var.project_id
  region        = var.region
  bucket_name   = "pfi-rm-lumbar-artifacts-2026-ef"
  create_bucket = false # EXISTING_EXTERNAL
}

module "iam" {
  source = "../../modules/iam"

  project_id = var.project_id

  service_accounts = {
    # EXISTING_EXTERNAL — found in the CLOUD-00 audit, not created here.
    backend_runtime = {
      create = false
      email  = "pfi-backend-runtime@${var.project_id}.iam.gserviceaccount.com"
    }
    ai_runtime = {
      create = false
      email  = "pfi-ai-runtime@${var.project_id}.iam.gserviceaccount.com"
    }
    # NEW — no dedicated Frontend runtime identity exists yet; least
    # privilege means it must not reuse an existing Backend/AI account.
    frontend_runtime = {
      create       = true
      account_id   = "pfi-frontend-runtime-dev"
      display_name = "PFI Frontend Runtime (dev)"
    }
  }

  # The GitHub artifact-push identity is NOT a runtime service account and
  # lives entirely in infra/terraform/platform/identity (CLOUD-03) -- this
  # module only manages runtime identities (Backend/Frontend/AI Cloud Run)
  # and their project-level role grants.
  #
  # CLOUD-04: cloudsql.client is only meaningful once a Cloud SQL instance
  # exists to connect to (CLOUD-05) -- granting it now, while enable_cloud_sql
  # is false, would be an IAM change with no corresponding resource, and the
  # CLOUD-04 hard gate treats any unrequested IAM change on a preexisting
  # identity as a STOP condition. Gated behind the same flag as the instance
  # itself so the two always move together.
  project_iam_bindings = var.enable_cloud_sql ? [
    { member_key = "backend_runtime", role = "roles/cloudsql.client" },
  ] : []
}

# CLOUD-05: two containers ONLY — the Backend has exactly one persistence
# config env var (DATABASE_URL) and one auth secret (PFI_AUTH_JWT_SECRET),
# nothing else needs a Secret Manager entry yet. Renamed from the CLOUD-01
# placeholders (jwt-secret/db-password/internal-ai-token, never wired to
# anything real) to names that match what CLOUD-05 actually creates and
# consumes below. Container only — see modules/secrets/main.tf: this module
# has never had a google_secret_manager_secret_version resource, so no
# secret VALUE has ever been able to reach Terraform state through it.
module "secrets" {
  source = "../../modules/secrets"

  project_id = var.project_id
  enabled    = var.enable_secrets # false — see CLOUD_05_SECRET_MANAGER.md
  secret_names = [
    "pfi-dev-database-url", # full jdbc:postgresql://127.0.0.1:5432/... incl. credentials -- see below, the app has no separate user/password env vars
    "pfi-dev-jwt-secret",
  ]
}

module "cloud_sql" {
  source = "../../modules/cloud-sql"

  project_id    = var.project_id
  region        = var.region
  instance_name = "pfi-postgres-dev"
  enabled       = var.enable_cloud_sql # false — see CLOUD_05_COST_CHECK.md
  # tier, edition, disk_size_gb, availability_type, backup_enabled,
  # ipv4_enabled left at the module's own CLOUD-05 defaults (db-f1-micro
  # shared-core, ENTERPRISE edition, 10GB PD_SSD, ZONAL, backups off, public
  # IP + empty authorized_networks) -- see CLOUD_05_COST_OPTIMIZATION_REVIEW.md
  # for the corrected, sourced pricing/availability reasoning and
  # CLOUD_05_CLOUD_SQL_DESIGN.md for the full DEV/DEMO/future comparison.
}

module "monitoring" {
  source = "../../modules/monitoring"

  project_id = var.project_id
  enabled    = var.enable_monitoring # false — CLOUD-07
}

# CLOUD-05: computed once here (not inline in the module call below) so the
# memory<->postgres transition is a single, reviewable diff -- flipping
# enable_cloud_sql changes exactly these three locals and nothing else about
# the module call itself. The image is never rebuilt for this transition:
# same immutable backend:dee0826... digest, different runtime configuration.
locals {
  backend_persistence_env_vars = var.enable_cloud_sql ? [
    { name = "PFI_PERSISTENCE_MODE", value = "postgres" },
    ] : [
    # CLOUD-05 is the persistence stage; no Cloud SQL exists yet
    # (enable_cloud_sql = false) and none is created here. The image's own
    # Dockerfile ENV already defaults to "memory" -- this line makes that
    # choice explicit in the infra that deploys it rather than relying on an
    # implicit image default a future reader might not see. SecurityStartup
    # Validator's "postgres required" rule only fires under the
    # production/prod Spring profile, which this DEV deployment never sets,
    # so this is safe and intentional, not a workaround.
    { name = "PFI_PERSISTENCE_MODE", value = "memory" },
  ]

  # Only DATABASE_URL and the JWT secret come from Secret Manager -- the
  # Backend has exactly one connection-string env var (no separate
  # PFI_DB_USER/PFI_DB_PASSWORD), so the DB user+password live inside the
  # single pfi-dev-database-url secret VALUE, created out-of-band per
  # CLOUD_05_SECRET_MANAGER.md, never by this terraform apply.
  backend_secret_env_vars = (var.enable_cloud_sql && var.enable_secrets) ? [
    { name = "DATABASE_URL", secret_name = "pfi-dev-database-url", version = "latest" },
    { name = "PFI_AUTH_JWT_SECRET", secret_name = "pfi-dev-jwt-secret", version = "latest" },
  ] : []

  # The Cloud SQL Auth Proxy sidecar only exists once Cloud SQL itself does.
  backend_cloudsql_sidecar = var.enable_cloud_sql ? {
    instance_connection_name = module.cloud_sql.connection_name
  } : null
}

module "backend_cloud_run" {
  source = "../../modules/cloud-run-service"

  project_id            = var.project_id
  region                = var.region
  name                  = "pfi-backend-${var.environment}"
  image                 = var.backend_image
  service_account_email = module.iam.service_account_emails["backend_runtime"]
  min_instance_count    = 0 # DEV: scale to zero
  max_instance_count    = 2
  cpu                   = "1"
  memory                = "512Mi"
  concurrency           = 80
  timeout_seconds       = 300
  allow_unauthenticated = true
  # CLOUD-04: explicit, not just relying on the module default -- the live
  # Backend service (already created) carries no startup-cpu-boost
  # annotation at all. null here means Terraform never tries to add one,
  # so this environment's declared config matches GCP's actual state
  # exactly and stops showing a perpetual drift on every future plan.
  startup_cpu_boost = null
  # CLOUD-04: /actuator/health was never a real endpoint -- Spring Boot
  # Actuator is not even a dependency of this project (confirmed: no
  # "actuator" reference anywhere in pom.xml). The CLOUD-01 401 finding was
  # testing a path that doesn't exist; AuthFilter fails closed on any
  # unrecognized path by design. The real, already-existing, already-tested
  # public liveness endpoint is /api/system/health (AuthFilter.
  # PUBLIC_LIVENESS_PATHS, SystemController#health) -- minimal
  # {"status":"ok"} body, no DB/AI/secrets/infra detail, covered by
  # SecurityAuthorizationIntegrationTest#anonymousSystemHealthIsPublicAndMinimal.
  # No Backend code or branch was needed for this.
  health_check_path = "/api/system/health"

  # CLOUD-05: PFI_PERSISTENCE_MODE now comes from local.backend_persistence_env_vars
  # (memory when enable_cloud_sql = false, postgres when true) instead of being
  # hardcoded here — see the locals block above this module call.
  env_vars = concat(local.backend_persistence_env_vars, [
    # DEV-only: enables POST /api/auth/demo-doctor so the smoke test can
    # obtain a real JWT without seeding an account against a database that
    # does not exist yet. Defaults to false in the image; never set true
    # outside DEV.
    { name = "PFI_AUTH_DEMO_ENABLED", value = "true" },
    # CLOUD-04: the real Frontend origin (see data.google_cloud_run_v2_service
    # .frontend_lookup above), not a predicted one -- exact HTTPS origin, no
    # wildcard. The earlier predicted-URL entry is removed: no consumer was
    # ever confirmed to use it, keeping it would just be a redundant, unused
    # allowlist entry (the whole point of the fix above is not needing a
    # prediction anymore). Existing localhost/Vercel defaults kept as-is for
    # other dev-facing contexts that already rely on them.
    { name = "PFI_CORS_ALLOWED_ORIGINS", value = "http://localhost:5173,http://localhost:3000,http://localhost:4173,http://127.0.0.1:5173,https://pfi-mvp-test-enzo-frontend.vercel.app,${data.google_cloud_run_v2_service.frontend_lookup.uri}" },
  ])

  # CLOUD-05: empty/null when enable_cloud_sql (and/or enable_secrets) is
  # false, so the Backend module call stays byte-identical to pre-CLOUD-05
  # in that default state.
  secret_env_vars  = local.backend_secret_env_vars
  cloudsql_sidecar = local.backend_cloudsql_sidecar
}

# CLOUD-05: resource-scoped, not project-wide -- roles/secretmanager.secretAccessor
# is granted per-secret, only to pfi-backend-runtime, only for the two secrets
# the Backend actually reads (DATABASE_URL, PFI_AUTH_JWT_SECRET). Frontend and
# the AI service get no Secret Manager access at all (never referenced here).
# Gated behind enable_secrets so no IAM binding is created while the secret
# containers themselves don't exist either.
resource "google_secret_manager_secret_iam_member" "backend_secret_access" {
  for_each = var.enable_secrets ? toset([
    "pfi-dev-database-url",
    "pfi-dev-jwt-secret",
  ]) : toset([])

  project   = var.project_id
  secret_id = each.value
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${module.iam.service_account_emails["backend_runtime"]}"

  depends_on = [module.secrets]
}

module "frontend_cloud_run" {
  source = "../../modules/cloud-run-service"

  project_id            = var.project_id
  region                = var.region
  name                  = "pfi-frontend-${var.environment}"
  image                 = var.frontend_image
  service_account_email = module.iam.service_account_emails["frontend_runtime"]
  min_instance_count    = 0
  max_instance_count    = 2
  cpu                   = "1"
  memory                = "256Mi"
  # CLOUD-04 fix (option B, authorized): Cloud Run's own admission control
  # rejects memory < 512Mi when CPU is NOT throttled during idle
  # (cpu_idle=false, the module's -- and GCP's -- default). Confirmed the
  # hard way: the first apply attempt failed with exactly this error.
  # Frontend is a static-file-serving nginx container with no background
  # work to do between requests, so throttling CPU to (near) zero while
  # idle is not just a workaround for the 512Mi floor -- it is the more
  # correct, cheaper setting for a scale-to-zero DEV service, which is why
  # this was chosen over raising memory to 512Mi.
  cpu_idle              = true
  concurrency           = 80
  allow_unauthenticated = true
  # CLOUD-04: the image's nginx (docker/nginx.conf) listens on 80, confirmed
  # in the Frontend repo -- the module's port default of 8080 would point
  # Cloud Run's health/traffic checks at a port nothing listens on and the
  # revision would never become ready. No image rebuild involved: this only
  # tells Cloud Run which port the EXISTING image's server already uses.
  port              = 80
  health_check_path = "/"

  env_vars = [
    { name = "BACKEND_URL", value = module.backend_cloud_run.url },
  ]
}

# CLOUD-04 explicitly excludes deploying a new AI Cloud Run service --
# enable_ai_service defaults to false and must stay false for this stage.
# The pre-existing pfi-ai-module service is confirmed untouched by this
# environment (module never instantiated when the flag is false) and is not
# reused blindly here.
module "ai_cloud_run" {
  count  = var.enable_ai_service ? 1 : 0
  source = "../../modules/cloud-run-service"

  project_id            = var.project_id
  region                = var.region
  name                  = "pfi-ai-service-${var.environment}"
  image                 = var.ai_image
  service_account_email = module.iam.service_account_emails["ai_runtime"]
  min_instance_count    = 0
  max_instance_count    = 1
  cpu                   = "2"
  memory                = "2Gi"
  concurrency           = 4
  allow_unauthenticated = false # internal only — reached via the Backend, never the public internet
  health_check_path     = "/health"
}
