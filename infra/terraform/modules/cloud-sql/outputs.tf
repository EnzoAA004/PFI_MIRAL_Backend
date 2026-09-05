output "connection_name" {
  value = var.enabled ? google_sql_database_instance.this[0].connection_name : null
}

output "database_name" {
  value = var.enabled ? google_sql_database.app[0].name : null
}

output "public_ip_address" {
  description = "Informational only — the Backend never connects via this address directly, only through the Cloud SQL Auth Proxy sidecar's IAM-authenticated tunnel."
  value       = var.enabled ? google_sql_database_instance.this[0].public_ip_address : null
}
