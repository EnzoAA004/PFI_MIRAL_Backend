output "service_account_email" {
  value = google_service_account.artifact_pusher.email
}

output "pool_name" {
  value = google_iam_workload_identity_pool.github.name
}

output "provider_name" {
  value = google_iam_workload_identity_pool_provider.github.name
}
