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

# CLOUD-04: Cloud Run v2 default service URLs are deterministic
# (https://<service>-<project-number>.<region>.run.app), confirmed against the
# pre-existing pfi-ai-module service's own URL in the CLOUD-00 audit. Used
# below to give the Backend a CORS origin for the Frontend without a circular
# module dependency (Frontend already depends on the Backend's URL for
# BACKEND_URL; the Backend cannot also depend on the Frontend module's actual
# output without a cycle).
data "google_project" "current" {
  project_id = var.project_id
}

locals {
  frontend_predicted_url = "https://pfi-frontend-${var.environment}-${data.google_project.current.number}.${var.region}.run.app"
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

module "secrets" {
  source = "../../modules/secrets"

  project_id   = var.project_id
  enabled      = false # secretmanager.googleapis.com not enabled; real values never in Terraform
  secret_names = ["jwt-secret", "db-password", "internal-ai-token"]
}

module "cloud_sql" {
  source = "../../modules/cloud-sql"

  project_id    = var.project_id
  region        = var.region
  instance_name = "pfi-postgres-dev"
  enabled       = var.enable_cloud_sql # false — see CLOUD_COST_MODEL.md
  tier          = "db-f1-micro"
  disk_size_gb  = 10
}

module "monitoring" {
  source = "../../modules/monitoring"

  project_id = var.project_id
  enabled    = var.enable_monitoring # false — CLOUD-07
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

  env_vars = [
    # CLOUD-05 is the persistence stage; no Cloud SQL exists yet
    # (enable_cloud_sql = false) and none is created here. The image's own
    # Dockerfile ENV already defaults to "memory" -- this line makes that
    # choice explicit in the infra that deploys it rather than relying on an
    # implicit image default a future reader might not see. SecurityStartup
    # Validator's "postgres required" rule only fires under the
    # production/prod Spring profile, which this DEV deployment never sets,
    # so this is safe and intentional, not a workaround.
    { name = "PFI_PERSISTENCE_MODE", value = "memory" },
    # DEV-only: enables POST /api/auth/demo-doctor so the smoke test can
    # obtain a real JWT without seeding an account against a database that
    # does not exist yet. Defaults to false in the image; never set true
    # outside DEV.
    { name = "PFI_AUTH_DEMO_ENABLED", value = "true" },
    # Breaks the Backend<->Frontend circular module dependency (see
    # local.frontend_predicted_url above) -- exact HTTPS origin, no
    # wildcard, keeping the existing localhost/Vercel defaults so other
    # dev-facing contexts that already rely on them keep working.
    { name = "PFI_CORS_ALLOWED_ORIGINS", value = "http://localhost:5173,http://localhost:3000,http://localhost:4173,http://127.0.0.1:5173,https://pfi-mvp-test-enzo-frontend.vercel.app,${local.frontend_predicted_url}" },
  ]
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
