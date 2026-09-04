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

module "artifact_registry" {
  source = "../../modules/artifact-registry"

  project_id        = var.project_id
  region            = var.region
  repository_id     = "pfi-containers"
  create_repository = false # EXISTING_EXTERNAL
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
    # NEW — dedicated CI/CD deploy identity, distinct from every runtime
    # identity above, per ADR-003.
    ci_deploy = {
      create       = true
      account_id   = "pfi-ci-deploy-dev"
      display_name = "PFI CI/CD Deploy (dev)"
    }
  }

  project_iam_bindings = [
    { member_key = "ci_deploy", role = "roles/artifactregistry.writer" },
    { member_key = "ci_deploy", role = "roles/run.developer" },
    { member_key = "ci_deploy", role = "roles/iam.serviceAccountUser" },
    { member_key = "backend_runtime", role = "roles/cloudsql.client" },
  ]

  enable_wif                 = true # preview only — see CLOUD_SECURITY_MODEL.md, never applied by CLOUD-01
  wif_pool_id                = "github-pool-dev"
  wif_provider_id            = "github-provider-dev"
  github_repository          = var.github_repository
  deploy_service_account_key = "ci_deploy"
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
  min_instance_count    = 0
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
  min_instance_count    = 0
  max_instance_count    = 1
  cpu                   = "2"
  memory                = "2Gi"
  concurrency           = 4
  allow_unauthenticated = false # internal only — reached via the Backend, never the public internet
  health_check_path     = "/health"
}
