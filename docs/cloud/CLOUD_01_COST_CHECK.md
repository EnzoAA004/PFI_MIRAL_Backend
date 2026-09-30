# CLOUD-01 — Cost Check

No cost was incurred producing this document — no resource was created, and no billed API call
was made (the read-only `gcloud describe/list` calls used to re-verify state are free). This
refines `CLOUD_COST_MODEL.md` using the exact resource shapes actually encoded in the CLOUD-01
Terraform configuration, not hypothetical ones.

## What the DEV configuration would actually cost if applied (it was not)

| Resource (from `environments/dev`) | Config in `.tf` | Always-on? | Notes |
|---|---|---|---|
| `pfi-backend-dev` (Cloud Run) | 1 vCPU / 512Mi, `min_instance_count=0`, `max=2` | No — scale to zero | Same pay-per-request shape as the pre-existing `pfi-ai-module` (which also has no `minScale`) |
| `pfi-frontend-dev` (Cloud Run) | 1 vCPU / 256Mi, `min_instance_count=0`, `max=2` | No | Smaller memory footprint than Backend |
| `pfi-ai-service-dev` (Cloud Run) | 2 vCPU / 2Gi, `min_instance_count=0`, `max=1` | No | Largest per-request footprint of the three; still zero idle cost |
| 2 new service accounts (`frontend_runtime`, `ci_deploy`) | — | N/A | IAM resources carry no cost |
| 4 `google_project_iam_member` bindings | — | N/A | Free |
| WIF pool + provider + impersonation binding | — | N/A | Free (Workload Identity Federation itself has no per-use charge) |
| Artifact Registry (`pfi-containers`, existing) | Unchanged — `create_repository=false` | N/A | No new storage added by this plan; only future CI pushes grow it |
| Storage bucket (existing) | Unchanged — `create_bucket=false` | N/A | No new storage added |
| Secret Manager | `enabled=false` | — | **$0**, nothing created |
| Cloud SQL | `enabled=false` | — | **$0**, nothing created — this is the number that matters most, since Cloud SQL is the one line item in `CLOUD_COST_MODEL.md` that isn't scale-to-zero |
| Monitoring alert policy | `enabled=false` | — | **$0**, nothing created |

**If this exact `dev` configuration were applied today: the only components with any billing
exposure are the three new Cloud Run services, and all three default to zero minimum instances —
meaning near-$0 while idle, billed only for actual request-serving time, at the same
per-vCPU-second / per-GiB-second rates already documented and sourced in `CLOUD_COST_MODEL.md`.**
No fixed, always-on cost is introduced by this configuration as it stands.

## What changes for DEMO

Only two numbers differ from DEV: `min_instance_count=1` on both `pfi-backend-demo` and
`pfi-frontend-demo` (so those two, unlike DEV, would bill for allocated idle CPU/memory whenever
`enable_cloud_sql`/actual deploy happens — not applicable today since nothing is applied), and a
larger, still-disabled Cloud SQL tier (`db-custom-1-3840` vs. `db-f1-micro`) documented for future
reliability. Both remain **$0 while `enable_cloud_sql=false` and nothing is deployed.**

## Special attention items, re-confirmed against this exact configuration

- **Cloud SQL**: `enabled=false` in both environments' `.tf` files — verified by reading the
  files, not just asserted. Zero risk of accidental creation via `terraform plan` (a `count=0`
  resource is never proposed) or `terraform apply` (never run this stage regardless).
- **GPU**: not referenced anywhere in this Terraform project. No `google_compute_instance`, no
  accelerator config exists in any `.tf` file.
- **Artifact scanning**: not configured (no `google_artifact_registry_repository` vulnerability
  scanning resource written) — still an open item for a later stage, unrelated to this plan's cost.
- **Egress**: the AI service stays `allow_unauthenticated=false` (private), reachable only via the
  Backend's service account — no public internet egress path is opened by this configuration
  beyond what Frontend/Backend already need to serve normal traffic.

## Sources

Reuses the sourced pricing from `CLOUD_COST_MODEL.md` (Cloud Run, Cloud SQL, Artifact Registry,
Cloud Storage, Secret Manager, egress — all cited there with URLs). No new pricing claim is made in
this document that isn't already sourced there.
