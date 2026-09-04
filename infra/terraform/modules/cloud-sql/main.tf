# enabled = false in every environment shipped by CLOUD-01. This resource
# must never appear as a planned "+ create" until CLOUD-05 explicitly
# authorizes the cost and enables sqladmin.googleapis.com.

resource "google_sql_database_instance" "this" {
  count = var.enabled ? 1 : 0

  project          = var.project_id
  name             = var.instance_name
  region           = var.region
  database_version = var.database_version

  settings {
    tier      = var.tier
    disk_size = var.disk_size_gb

    backup_configuration {
      enabled = var.backup_enabled
    }

    ip_configuration {
      ipv4_enabled = var.ipv4_enabled
    }
  }

  deletion_protection = var.deletion_protection
}
