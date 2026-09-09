output "frontend_url" {
  value = module.frontend_cloud_run.url
}

output "backend_url" {
  value = module.backend_cloud_run.url
}

output "ai_url" {
  # CLOUD-04: module.ai_cloud_run is only instantiated when enable_ai_service
  # = true (stays false this stage) -- null otherwise, never an error.
  value     = var.enable_ai_service ? module.ai_cloud_run[0].url : null
  sensitive = true # internal-only service; not meant for casual display
}

output "artifact_repository" {
  value = module.artifact_registry.repository_url
}

output "database_connection_name" {
  value = var.enable_cloud_sql ? module.cloud_sql.connection_name : null
}

output "runtime_service_accounts" {
  value = module.iam.service_account_emails
}
