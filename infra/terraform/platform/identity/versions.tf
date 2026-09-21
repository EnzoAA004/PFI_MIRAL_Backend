terraform {
  required_version = ">= 1.9.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 8.1"
    }
  }

  # Local backend, same as environments/{dev,demo} -- no GCS state bucket
  # exists yet (see CLOUD_01_TERRAFORM_FOUNDATION.md "Remote state").
  backend "local" {}
}

provider "google" {
  project = var.project_id
  region  = var.region
}
