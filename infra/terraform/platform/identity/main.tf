# Platform-level, environment-agnostic root module. This is the ONLY place
# WIF and the artifact-push identity are created -- environments/dev and
# environments/demo reference the resulting service account email as a
# plain input, they never instantiate modules/workload-identity themselves.
# Run this root module's own `terraform init/plan/apply` independently of
# environments/{dev,demo}.

module "workload_identity" {
  source = "../../modules/workload-identity"

  project_id          = var.project_id
  wif_pool_id         = "github-pool"
  wif_provider_id     = "github-provider"
  service_account_id  = "pfi-github-artifact-pusher"
  github_repositories = var.github_repositories
}

module "artifact_registry" {
  source = "../../modules/artifact-registry"

  project_id        = var.project_id
  region            = var.region
  repository_id     = var.artifact_repository_id
  create_repository = false # EXISTING_EXTERNAL -- pfi-containers, never created/imported here

  writer_members = [
    "serviceAccount:${module.workload_identity.service_account_email}",
  ]
}
