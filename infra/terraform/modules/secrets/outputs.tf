output "secret_ids" {
  value = var.enabled ? [for s in google_secret_manager_secret.this : s.secret_id] : []
}
