output "repository_id" {
  value = var.create_repository ? google_artifact_registry_repository.this[0].repository_id : data.google_artifact_registry_repository.existing[0].repository_id
}

output "repository_url" {
  value = "${var.region}-docker.pkg.dev/${var.project_id}/${var.repository_id}"
}
