output "artifact_pusher_email" {
  value = module.workload_identity.service_account_email
}

output "wif_pool_name" {
  value = module.workload_identity.pool_name
}

output "wif_provider_name" {
  value = module.workload_identity.provider_name
}
