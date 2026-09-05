# CLOUD-05 — Security Review

## Checklist and verdicts

| Item | Verdict | Evidence |
|---|---|---|
| Frontend has no DB access | ✅ PASS | `frontend_runtime` SA appears nowhere in `module.iam.project_iam_bindings` or the new secret IAM bindings; Plan 2 shows zero Frontend resource changes. |
| Frontend has no Secret Manager access | ✅ PASS | `google_secret_manager_secret_iam_member.backend_secret_access` only ever grants `member = "serviceAccount:${...backend_runtime}"` — no Frontend/AI member reference exists anywhere in this stage's Terraform. |
| Backend accesses Cloud SQL only via the sidecar, plus exactly the 2 secrets it needs | ✅ PASS | `backend_runtime` gains `roles/cloudsql.client` (needed for the Auth Proxy) and `roles/secretmanager.secretAccessor` on `pfi-dev-database-url` + `pfi-dev-jwt-secret` only — no other secret, no broader role. |
| No project-wide Secret Manager grant | ✅ PASS | Binding is a `google_secret_manager_secret_iam_member` (resource-scoped, `secret_id` per entry), never a `google_project_iam_member` with a Secret Manager role. |
| No project-wide Cloud SQL grant beyond what's unavoidable | ✅ PASS (documented tradeoff) | `roles/cloudsql.client` is inherently a project-level role in GCP's IAM model (Cloud SQL has no resource-level IAM binding equivalent for client access) — this is the minimum role for the Auth Proxy to function, not a broader grant of convenience. |
| DB has no broad internet exposure | ✅ PASS | `ip_configuration` sets `ipv4_enabled = true` with **no** `authorized_networks` block and no variable exposing one — zero IP ranges are ever authorized, including no `0.0.0.0/0`. |
| DB password never in Git, Terraform variable values, or state | ✅ PASS | No `google_sql_user` resource exists in this design; the user/password are created out-of-band via `gcloud sql users create`, documented in `CLOUD_05_SECRET_MANAGER.md`, never referenced by any `.tf` file. |
| DB password never in Cloud Run plaintext env vars | ✅ PASS | `DATABASE_URL` is sourced via `secret_key_ref` (`value_source`), never a literal `env { value = ... }` — confirmed in the Plan 2 diff. |
| JWT secret is Secret-Manager-backed for this deployment, not the insecure image default | ✅ PASS (once `enable_secrets=true` is applied) | `PFI_AUTH_JWT_SECRET` is wired via `secret_env_vars` when both `enable_cloud_sql` and `enable_secrets` are true, replacing the CLOUD-00-flagged `pfi-demo-change-me-2026` default for this environment. |
| CI pusher (`pfi-github-artifact-pusher`) has no DB access | ✅ PASS | That identity lives entirely in `infra/terraform/platform/identity` (CLOUD-03), untouched by this stage; not referenced anywhere in `environments/dev`'s Cloud SQL or Secret Manager resources. |
| AI service gains no new access | ✅ PASS | `ai_runtime` is not referenced in any new resource this stage adds; Plan 2 shows zero changes related to the AI module (`enable_ai_service` stays `false`, untouched). |
| No `random_password` value silently persisted in state | ✅ PASS | No `random_password` resource exists anywhere in this design — the credential strategy avoids it entirely (see `CLOUD_05_CLOUD_SQL_DESIGN.md` "Credential strategy"). |
| `deletion_protection` on while administered | ✅ PASS | `deletion_protection = true` is the module default, unchanged by any environment override; teardown requires an explicit, separately authorized flip documented in `CLOUD_05_CLOUD_SQL_DESIGN.md`. |
| No secret value in any Terraform plan/apply output | ✅ PASS | Confirmed directly in the captured Plan 2 output (`CLOUD_05_TERRAFORM_PLAN_OUTPUT.txt`) — secret-adjacent fields show only `(known after apply)`/`(sensitive value)`/the **secret resource name**, never a value. |
| `sqladmin.googleapis.com` / `secretmanager.googleapis.com` not enabled this pass | ✅ PASS (intentional) | Both remain disabled — confirmed via failing `gcloud sql instances list` / `gcloud secrets list` calls, which is the expected state for a plan-only stage, not a bug. |

## Residual risks / follow-ups for the activation step (not blockers to this design)

- **Application-log leakage of `DATABASE_URL`** was not audited in this pass (out of CLOUD-05's
  read-only Terraform-design scope) — flagged as a check to run during the future authorized
  activation step, per `CLOUD_05_SECRET_MANAGER.md`'s verification plan.
- **`roles/cloudsql.client` is project-scoped by GCP's own IAM model**, not a Terraform choice — this
  is the accepted minimum for the Auth Proxy sidecar pattern; no finer-grained alternative exists in
  Cloud SQL's IAM surface today.
- **Manual out-of-band steps** (DB user creation, secret value population) are, by design, not
  reproducible via `terraform apply` alone — each new environment (e.g. a future DEMO) repeats this
  procedure manually. Acceptable at this project's scale (two environments, no automated environment
  provisioning), but worth automating (e.g. via a documented runbook script, still never through
  Terraform) if a third environment is ever added.

## Overall verdict

**No security finding blocks this design.** All explicit constraints from the CLOUD-05 kickoff
(no public DB exposure, no plaintext secret in state, least-privilege IAM, no cross-service secret
leakage) are satisfied by the Terraform code as written and confirmed by the Plan 2 diff. This design
is ready for cost approval and, separately, apply authorization — neither of which this document
grants.
