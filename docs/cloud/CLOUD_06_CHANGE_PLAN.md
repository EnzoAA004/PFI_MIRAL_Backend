# CLOUD-06 — Change Plan

## Terraform: `environments/demo`, real plan against live state

Run via the established `hashicorp/terraform:1.9` Docker image + `GOOGLE_OAUTH_ACCESS_TOKEN` (the
operator's own ADC, not a workflow identity — no CI ran this stage). `terraform fmt -recursive -diff`
and `terraform validate` both passed cleanly after the Phase 5 fixes below. `demo` has never been
applied — no `terraform.tfstate` existed before this plan, so every result is a from-scratch "+
create", not a diff against a prior demo deploy.

Full raw output: `CLOUD_06_TERRAFORM_PLAN_DEMO_OUTPUT.txt`.

**Plan: 5 to add, 0 to change, 0 to destroy.**

| Resource | Action |
|---|---|
| `module.backend_cloud_run.google_cloud_run_v2_service.this` | + create |
| `module.backend_cloud_run.google_cloud_run_v2_service_iam_member.public[0]` | + create |
| `module.frontend_cloud_run.google_cloud_run_v2_service.this` | + create |
| `module.frontend_cloud_run.google_cloud_run_v2_service_iam_member.public[0]` | + create |
| `module.iam.google_service_account.created["frontend_runtime"]` | + create |

**Confirmed absent** (matches Phase 6's explicit gate — none of these appear, so nothing here
triggers a STOP): Cloud SQL (`enable_cloud_sql=false`), Secret Manager (`enable_secrets=false`),
Monitoring (`enable_monitoring=false`), AI Cloud Run service (`enable_ai_service=false`), WIF, new
Artifact Registry repository, new/replaced Storage bucket. No `~ update` and no `- destroy` line
anywhere in the plan — no drift, no replace.

Plan variables used: `backend_image`/`frontend_image` set to the exact DEV-validated digests
(`backend:dee0826c9e1121e348192058acde841b6f933608`,
`frontend:1c77231e2e1833bada6dc1c0f4f622f1d32e3470`) — the same immutability requirement CLOUD-06's
promotion workflow enforces automatically; `ai_image` set to a placeholder since `enable_ai_service`
stays `false` (the variable is required by Terraform's schema but the module it would feed is never
instantiated).

## Demo Terraform fixes applied (Phase 5), enabling this clean plan

See `CLOUD_06_DEV_TO_DEMO_PROMOTION.md` "Demo Terraform fixes" for the four bugs found and corrected
(`health_check_path`, Frontend `port`/`cpu_idle`, unconditional AI service, unconditional Cloud SQL
IAM binding) plus the new `enable_secrets`/`enable_ai_service`/`*_min_instances` variables and the
DEV-mirrored persistence/secret/sidecar `locals` pattern. Without these fixes, a real `terraform
apply` of the pre-CLOUD-06 `demo/main.tf` would have: rejected the Frontend service at the API level
(memory-floor rule), pointed its health check at a nonexistent path, unconditionally created a
dedicated AI service, and unconditionally granted a Cloud SQL IAM role with no instance to scope it
to — none of which the original CLOUD-01 plan-only pass ever caught, since `demo` was never actually
planned against live state before now.

## Scripts and workflows validated (Phase 13, no cloud writes)

| Artifact | Validation performed | Result |
|---|---|---|
| `cloud-06-validate-dev.yml` | Parsed with PyYAML, `jobs` structure confirmed | OK, 1 job |
| `cloud-06-promote-demo.yml` | Parsed with PyYAML, `jobs` structure confirmed | OK, 1 job |
| `cloud-06-rollback-demo.yml` | Parsed with PyYAML, `jobs` structure confirmed | OK, 1 job |
| `scripts/cloud/verify-image-digest.sh` | `bash -n` syntax check + 3 synthetic dry-runs (matching digest → pass; mismatched digest → fail; `latest` tag → refused) against a mocked `gcloud` on `$PATH` | All 3 scenarios behaved as designed |
| `scripts/cloud/cloud-06-smoke.sh` | `bash -n` syntax check | OK |

`actionlint` and `shellcheck` were not installed (not available on this machine; not installed per
the same "don't install `act` unless available" instruction extended to other optional local tooling)
— PyYAML parsing plus manual review covers structural correctness; a full `actionlint` pass is a
reasonable follow-up before these workflows are ever actually run, not a blocker for this design pass.

## Git status

Branches created from CLOUD-05's final pushed HEAD (`b456333`, not `c5c1d23` or CLOUD-04's `8ac7c0f`,
per explicit instruction): `cloud/cloud-06-promotion-backend`. No Frontend or AI change was needed
this stage (all CLOUD-06 work is Backend-repo Terraform + GitHub Actions + docs), so no Frontend/AI
branch was created. Nothing has been committed yet — left as `READY_TO_COMMIT` pending explicit
authorization, per instruction ("NO commit automático si no está autorizado").
