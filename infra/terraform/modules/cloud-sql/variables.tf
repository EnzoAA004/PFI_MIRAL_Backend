variable "project_id" {
  type = string
}

variable "region" {
  type = string
}

variable "instance_name" {
  type = string
}

variable "enabled" {
  type        = bool
  description = "false by default, required by CLOUD-01. sqladmin.googleapis.com is not enabled on the audited project — flipping this on requires enabling that API and accepting Cloud SQL's always-on cost (see CLOUD_05_COST_CHECK.md), as its own explicit, separately authorized step (CLOUD-05)."
  default     = false
}

variable "database_version" {
  type        = string
  description = "CLOUD-05: kept at the CLOUD-01 default. POSTGRES_15 is a current, standard-support major version — confirmed NOT to require Cloud SQL's \"Extended support\" pricing surcharge (unlike the now-deprecated db-f1-micro/db-g1-small shared-core tiers, which the CLOUD-05 pricing audit found ARE extended-support-only today)."
  default     = "POSTGRES_15"
}

variable "database_name" {
  type        = string
  description = "The application database name (a google_sql_database resource, not just the instance)."
  default     = "pfi"
}

variable "tier" {
  type        = string
  description = <<-EOT
    CLOUD-05 correction: db-f1-micro (the CLOUD-01 default) is NOT a valid
    choice for a new instance today. Confirmed via the live Cloud Billing
    Catalog API (services/9662-B51E-5089 skus): every db-f1-micro/db-g1-small
    SKU found for us-central1 is versioned "Extended support f1-micro/
    g1-small vNN" tied to already-EOL PostgreSQL major versions (9.6-13) --
    shared-core machine types were removed from new-instance ordering. The
    smallest currently orderable configuration is a custom machine type,
    billed per resource: Zonal vCPU $0.0413/hour + Zonal RAM $0.0070/GiB-hour
    (same source). Default here is the smallest valid custom tier for DEV:
    1 vCPU, paired with the smallest allowed RAM via `memory_mb`-equivalent
    sizing (db-custom-1-3840 = 1 vCPU / 3.75 GiB, Cloud SQL's documented
    minimum RAM-per-vCPU ratio) -- see CLOUD_05_CLOUD_SQL_DESIGN.md for the
    full DEV/DEMO/future comparison table with sourced pricing.
  EOT
  default     = "db-custom-1-3840"
}

variable "disk_type" {
  type        = string
  description = "PD_SSD (the only reasonable choice at this scale — PD_HDD is cheaper per GB but Cloud SQL recommends SSD even for small instances due to IOPS)."
  default     = "PD_SSD"
}

variable "disk_size_gb" {
  type        = number
  description = "10 is Cloud SQL's documented minimum disk size — never sized down further, and not sized up without a demonstrated need."
  default     = 10
}

variable "disk_autoresize" {
  type        = bool
  description = "false for DEV: an unbounded, surprise storage-cost increase is worse than an out-of-space error the operator notices and responds to explicitly."
  default     = false
}

variable "availability_type" {
  type        = string
  description = "ZONAL (single-zone, no automatic failover) for DEV — REGIONAL (HA) roughly doubles both compute and storage cost (confirmed: Regional vCPU/RAM SKUs are exactly 2x the Zonal rate) for a benefit (automatic failover) a thesis DEV/DEMO environment does not need."
  default     = "ZONAL"
}

variable "backup_enabled" {
  type        = bool
  description = "false for DEV by default — see CLOUD_05_CLOUD_SQL_DESIGN.md \"Backup strategy\" for the cost-vs-utility reasoning (DEV data is synthetic/reproducible; DEMO should flip this to true)."
  default     = false
}

variable "ipv4_enabled" {
  type        = bool
  description = <<-EOT
    true here, deliberately -- the CLOUD-01 comment describing this as an
    "interim DEV convenience" pending private networking undersold it: for
    this project's chosen network strategy (Cloud SQL Auth Proxy sidecar in
    the same Cloud Run v2 service, IAM-authenticated), a public IP with an
    EMPTY authorized-networks list (see main.tf) is the intended, permanent
    design, not a stopgap. It avoids a VPC + Private Service Access
    connection (real, ongoing complexity this thesis project does not need)
    while never exposing Postgres to a raw, IP-authorized TCP connection --
    the Auth Proxy's authenticated tunnel is the only path in.
  EOT
  default     = true
}

variable "deletion_protection" {
  type        = bool
  description = "true while this instance is administered (CLOUD-05's own instruction). Teardown requires an explicit, separate, authorized step that first flips this to false — see CLOUD_05_CLOUD_SQL_DESIGN.md \"Teardown strategy\"."
  default     = true
}
