# Canonical version pins for this Terraform project.
#
# This file is a REFERENCE, not a runnable root module. Terraform has no
# native "inherit provider config from parent directory" mechanism across
# separate root modules, so each directory under environments/ that you
# actually run `terraform` in (dev, demo) declares its own identical copy
# of this block. Keep both in sync manually when bumping versions — see
# docs/cloud/CLOUD_01_TERRAFORM_FOUNDATION.md for the rationale.
#
# Detected locally: Terraform v1.15.8 (installed this stage via winget,
# HashiCorp official package). Latest available at design time: v1.16.1 —
# not required for this project; v1.9+ features are the actual floor used.
#
# Google provider: latest at design time is 8.1.0 (published 2026-09-01,
# registry.terraform.io/providers/hashicorp/google). Pinned to a `~> 8.1`
# minor-version range rather than exact-pinning, so patch releases are
# picked up automatically but a major/minor bump requires a deliberate
# version-file edit.

terraform {
  required_version = ">= 1.9.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 8.1"
    }
  }
}
