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
    CLOUD-05 re-audit correction (superseding the original CLOUD-05 pass):
    db-f1-micro IS a valid, currently-orderable tier for a new PostgreSQL 15
    instance. The original conclusion ("db-f1-micro only exists as an
    Extended-support SKU, removed from new-instance ordering") was WRONG --
    it came from grepping the Billing Catalog SKU descriptions for the
    literal substrings "f1-micro"/"g1-small", which only appear in
    Extended-support-labeled SKUs (tied to EOL major versions 9.6-13); the
    SAME tier's CURRENT, regular-support pricing is filed under a
    differently-worded description ("Micro instance"/"Small instance") that
    search missed, and the original pass also stopped after 3 pages
    (15,000 SKUs) of a service whose SKU catalog is actually ~40,500 SKUs
    (every page had a nextPageToken; pagination was never exhausted).
    Re-confirmed two ways: (1) the live Cloud SQL Admin API's tiers.list
    endpoint (does not require sqladmin.googleapis.com enabled -- a static
    reference listing, not a project resource) returns db-f1-micro with
    edition=ENTERPRISE and us-central1 in its supported region list; (2) the
    full, exhaustively-paged Billing Catalog SKU list contains
    "Cloud SQL for PostgreSQL: Zonal - Micro instance in Americas" (SKU
    C2D4-F7DF-B8D0) at $0.0105/hour for us-central1 -- a live, current,
    non-extended-support SKU. Default here is db-f1-micro: the minimum-cost
    DEV choice (see CLOUD_05_COST_OPTIMIZATION_REVIEW.md). Known limitations
    of shared-core tiers (db-f1-micro/db-g1-small): ~614 MiB RAM (fixed, not
    configurable), Google's own documentation states they are not covered by
    the Cloud SQL SLA and are not recommended for production -- acceptable
    for a thesis DEV/smoke-test environment, not for DEMO/prod-like use.
    Override to a dedicated custom tier (e.g. db-custom-1-3840, 1 vCPU/3.75
    GiB, $0.0413/vCPU-hr + $0.0070/GiB-hr Zonal, ~$51/month) for a future
    DEMO or prod-like environment that needs a real SLA and more headroom --
    see CLOUD_05_CLOUD_SQL_DESIGN.md's tier comparison table for both.
  EOT
  default     = "db-f1-micro"
}

variable "edition" {
  type        = string
  description = <<-EOT
    "ENTERPRISE" (default) is required for shared-core tiers (db-f1-micro,
    db-g1-small) -- confirmed via the live tiers.list API, which reports
    edition=ENTERPRISE for db-f1-micro. "ENTERPRISE_PLUS" is Google's
    dedicated-core-only edition (the C4/N4 machine types) and would reject a
    shared-core tier. Only change this alongside `tier` when moving to a
    dedicated machine type for a future DEMO/prod-like environment.
  EOT
  default     = "ENTERPRISE"
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
