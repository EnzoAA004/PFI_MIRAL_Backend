output "service_account_emails" {
  value = local.service_account_emails
}

output "wif_pool_name" {
  value = var.enable_wif ? google_iam_workload_identity_pool.github[0].name : null
}

output "wif_provider_name" {
  value = var.enable_wif ? google_iam_workload_identity_pool_provider.github[0].name : null
}
