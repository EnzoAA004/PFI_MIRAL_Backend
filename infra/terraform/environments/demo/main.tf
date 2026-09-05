terraform {
  required_version = ">= 1.9.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 8.1"
    }
  }

  backend "local" {}
}

provider "google" {
  project = var.project_id
  region  = var.region
}

module "artifact_registry" {
  source = "../../modules/artifact-registry"

  project_id        = var.project_id
  region            = var.region
  repository_id     = "pfi-containers"
  create_repository = false # EXISTING_EXTERNAL

  # No writer_members here -- owned exclusively by
  # infra/terraform/platform/identity (CLOUD-03), applied once.
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
    # EXISTING_EXTERNAL — same identities DEV uses. A DEMO Backend/AI runtime
    # does not get its own separate service account: least privilege is
    # enforced by which *roles*/secrets each environment's resources are
    # granted, not by minting a new identity per environment for its own
    # sake (Frontend is the one exception, same as DEV, since it previously
    # had no dedicated identity at all).
    backend_runtime = {
      create = false
      email  = "pfi-backend-runtime@${var.project_id}.iam.gserviceaccount.com"
    }
    ai_runtime = {
      create = false
      email  = "pfi-ai-runtime@${var.project_id}.iam.gserviceaccount.com"
    }
    frontend_runtime = {
      create       = true
      account_id   = "pfi-frontend-runtime-demo"
      display_name = "PFI Frontend Runtime (demo)"
    }
  }

  # CLOUD-06 fix: this binding was previously unconditional -- a real bug,
  # inherited from before CLOUD-04 established the pattern (see DEV's
  # module "iam" comment) of never granting an IAM role for a resource that
  # doesn't exist yet. Gated behind enable_cloud_sql exactly like DEV, so a
  # `terraform plan` with Cloud SQL off never shows an IAM change with no
  # corresponding instance.
  project_iam_bindings = var.enable_cloud_sql ? [
    { member_key = "backend_runtime", role = "roles/cloudsql.client" },
  ] : []
}

# CLOUD-06: two containers only, matching DEV's CLOUD-05 design exactly --
# see CLOUD_05_SECRET_MANAGER.md. Renamed from the CLOUD-01 placeholders
# (jwt-secret/db-password/internal-ai-token, never wired to anything real)
# to the names DEMO would actually create and consume if enabled.
module "secrets" {
  source = "../../modules/secrets"

  project_id = var.project_id
  enabled    = var.enable_secrets # false — CLOUD-06 requests no new cost/resource
  secret_names = [
    "pfi-demo-database-url",
    "pfi-demo-jwt-secret",
  ]
}

module "cloud_sql" {
  source = "../../modules/cloud-sql"

  project_id    = var.project_id
  region        = var.region
  instance_name = "pfi-postgres-demo"
  enabled       = var.enable_cloud_sql # false — CLOUD-06 requests no new cost/resource
  # DEMO keeps the dedicated db-custom-1-3840 tier (not DEV's shared-core
  # db-f1-micro) for demo-day reliability -- a live demo failing due to
  # shared-core CPU contention or the documented lack of SLA coverage on
  # db-f1-micro (see CLOUD_05_COST_OPTIMIZATION_REVIEW.md item 16) is a worse
  # outcome than the marginal cost difference. This is a deliberate,
  # documented choice, not an oversight -- revisit only if DEMO's own cost
  # authorization process decides otherwise.
  tier         = "db-custom-1-3840"
  disk_size_gb = 10
}

module "monitoring" {
  source = "../../modules/monitoring"

  project_id = var.project_id
  enabled    = var.enable_monitoring # false — CLOUD-07
}

# CLOUD-06: same locals pattern as DEV's CLOUD-05 design (see
# environments/dev/main.tf) so DEV and DEMO's persistence/secrets/sidecar
# wiring stay structurally identical -- the only thing that should ever
# differ between them is which flags are set and which image digest is
# deployed (and DEMO's image digest must equal DEV's, enforced by the
# CLOUD-06 promotion workflow's immutability check, not by Terraform).
locals {
  backend_persistence_env_vars = var.enable_cloud_sql ? [
    { name = "PFI_PERSISTENCE_MODE", value = "postgres" },
    ] : [
    { name = "PFI_PERSISTENCE_MODE", value = "memory" },
  ]

  backend_secret_env_vars = (var.enable_cloud_sql && var.enable_secrets) ? [
    { name = "DATABASE_URL", secret_name = "pfi-demo-database-url", version = "latest" },
    { name = "PFI_AUTH_JWT_SECRET", secret_name = "pfi-demo-jwt-secret", version = "latest" },
  ] : []

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
  min_instance_count    = var.backend_min_instances
  max_instance_count    = 2
  cpu                   = "1"
  memory                = "512Mi"
  concurrency           = 80
  timeout_seconds       = 300
  allow_unauthenticated = true
  # CLOUD-06 fix: /actuator/health is not a real endpoint (Spring Boot
  # Actuator is not a pom.xml dependency) -- this was the exact CLOUD-01
  # finding CLOUD-04 already fixed for DEV. The real, tested, public
  # liveness endpoint is /api/system/health (AuthFilter.PUBLIC_LIVENESS_PATHS).
  health_check_path = "/api/system/health"

  env_vars = concat(local.backend_persistence_env_vars, [
    # DEV-equivalent: enables POST /api/auth/demo-doctor so the CLOUD-06
    # DEV-validation and post-promotion smoke workflows can obtain a real
    # JWT. Same tradeoff DEV accepted in CLOUD-04/05 -- never true outside
    # DEV/DEMO smoke contexts.
    { name = "PFI_AUTH_DEMO_ENABLED", value = "true" },
    # CLOUD-06 known bootstrap gap (documented, not silently wrong): unlike
    # DEV (whose CORS list references the Frontend's real, already-existing
    # URL via a data source -- see environments/dev/main.tf), DEMO's
    # Frontend does not exist yet on a from-scratch first apply, and adding
    # a `data "google_cloud_run_v2_service"` lookup here would fail
    # `terraform plan` outright (not just show a diff) before DEMO is ever
    # created -- breaking CLOUD-06 Phase 6's "clean plan" expectation. DEMO's
    # exact Frontend origin is intentionally NOT predicted here (CLOUD-04
    # already found that predicting Cloud Run's URL format is wrong and
    # causes a real CORS failure) -- it must be added as an authorized
    # follow-up apply once the DEMO Frontend exists once, exactly mirroring
    # how DEV's own CORS fix happened.
    { name = "PFI_CORS_ALLOWED_ORIGINS", value = "http://localhost:5173,http://localhost:3000,http://localhost:4173,http://127.0.0.1:5173,https://pfi-mvp-test-enzo-frontend.vercel.app" },
  ])

  secret_env_vars  = local.backend_secret_env_vars
  cloudsql_sidecar = local.backend_cloudsql_sidecar
}

module "frontend_cloud_run" {
  source = "../../modules/cloud-run-service"

  project_id            = var.project_id
  region                = var.region
  name                  = "pfi-frontend-${var.environment}"
  image                 = var.frontend_image
  service_account_email = module.iam.service_account_emails["frontend_runtime"]
  min_instance_count    = var.frontend_min_instances
  max_instance_count    = 2
  cpu                   = "1"
  memory                = "256Mi"
  # CLOUD-06 fix: same admission-control rule CLOUD-04 hit for DEV's
  # Frontend -- memory < 512Mi requires cpu_idle=true (CPU throttled while
  # idle) or GCP rejects the service at apply time. Never set here before;
  # a DEMO Frontend apply would have failed the exact same way DEV's did.
  cpu_idle              = true
  concurrency           = 80
  allow_unauthenticated = true
  # CLOUD-06 fix: the image's nginx listens on 80, not the module's default
  # 8080 -- same CLOUD-04 DEV finding, never applied to DEMO until now.
  port              = 80
  health_check_path = "/"

  env_vars = [
    { name = "BACKEND_URL", value = module.backend_cloud_run.url },
  ]
}

# CLOUD-06 fix: previously unconditional (no `count`) -- a real bug, meaning
# an apply of this file as it stood would have ALWAYS created a dedicated
# DEMO AI Cloud Run service regardless of any flag, unlike DEV's already-
# correct count-gated pattern (see environments/dev/main.tf). Fixed to match
# DEV: the pre-existing pfi-ai-module stays untouched, no dedicated DEMO AI
# service is created unless enable_ai_service is explicitly authorized.
module "ai_cloud_run" {
  count  = var.enable_ai_service ? 1 : 0
  source = "../../modules/cloud-run-service"

  project_id            = var.project_id
  region                = var.region
  name                  = "pfi-ai-service-${var.environment}"
  image                 = var.ai_image
  service_account_email = module.iam.service_account_emails["ai_runtime"]
  min_instance_count    = 0 # tolerable single cold start for AI during a demo
  max_instance_count    = 1
  cpu                   = "2"
  memory                = "2Gi"
  concurrency           = 4
  allow_unauthenticated = false # internal only — reached via the Backend, never the public internet
  health_check_path     = "/health"
}
