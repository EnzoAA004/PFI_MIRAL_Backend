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
  create_repository = false

  # No writer_members here -- owned exclusively by
  # infra/terraform/platform/identity (CLOUD-03), applied once.
}

module "storage" {
  source = "../../modules/storage"

  project_id    = var.project_id
  region        = var.region
  bucket_name   = "pfi-rm-lumbar-artifacts-2026-ef"
  create_bucket = false
}

module "iam" {
  source = "../../modules/iam"

  project_id = var.project_id

  service_accounts = {
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

  # The GitHub artifact-push identity lives entirely in
  # infra/terraform/platform/identity (CLOUD-03) -- not a runtime SA, not
  # managed by this module.
  project_iam_bindings = [
    { member_key = "backend_runtime", role = "roles/cloudsql.client" },
  ]
}

module "secrets" {
  source = "../../modules/secrets"

  project_id   = var.project_id
  enabled      = false
  secret_names = ["jwt-secret", "db-password", "internal-ai-token"]
}

module "cloud_sql" {
  source = "../../modules/cloud-sql"

  project_id    = var.project_id
  region        = var.region
  instance_name = "pfi-postgres-demo"
  enabled       = var.enable_cloud_sql
  # DEMO tier documented as a small DEDICATED tier (not shared-core
  # db-f1-micro) for demo-day reliability — see CLOUD_COST_MODEL.md. Still
  # never created while enable_cloud_sql stays false.
  tier         = "db-custom-1-3840"
  disk_size_gb = 10
}

module "monitoring" {
  source = "../../modules/monitoring"

  project_id = var.project_id
  enabled    = var.enable_monitoring
}

module "backend_cloud_run" {
  source = "../../modules/cloud-run-service"

  project_id            = var.project_id
  region                = var.region
  name                  = "pfi-backend-${var.environment}"
  image                 = var.backend_image
  service_account_email = module.iam.service_account_emails["backend_runtime"]
  min_instance_count    = 1 # DEMO: no cold start during a live demo
  max_instance_count    = 2
  cpu                   = "1"
  memory                = "512Mi"
  concurrency           = 80
  timeout_seconds       = 300
  allow_unauthenticated = true
  health_check_path     = "/actuator/health"

  env_vars = [
    { name = "PFI_PERSISTENCE_MODE", value = "postgres" },
  ]
}

module "frontend_cloud_run" {
  source = "../../modules/cloud-run-service"

  project_id            = var.project_id
  region                = var.region
  name                  = "pfi-frontend-${var.environment}"
  image                 = var.frontend_image
  service_account_email = module.iam.service_account_emails["frontend_runtime"]
  min_instance_count    = 1 # DEMO: no cold start
  max_instance_count    = 2
  cpu                   = "1"
  memory                = "256Mi"
  concurrency           = 80
  allow_unauthenticated = true

  env_vars = [
    { name = "BACKEND_URL", value = module.backend_cloud_run.url },
  ]
}

module "ai_cloud_run" {
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
  allow_unauthenticated = false
  health_check_path     = "/health"
}
