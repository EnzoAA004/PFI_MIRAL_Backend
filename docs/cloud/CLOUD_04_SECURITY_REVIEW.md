# CLOUD-04 — Security Review

**Update (post-apply): Backend is live; every row below re-verified against the actual deployed
service, not just the plan.** `gcloud run services describe pfi-backend-dev` confirms
`serviceAccountName: pfi-backend-runtime@...` (never `pfi-github-artifact-pusher`), no
`secret_env_vars` present, `cpu-throttling: false` (a cost/perf detail, not a security one).
Frontend is not live yet (see `CLOUD_04_CHANGE_PLAN.md`) — its rows below describe the reviewed
plan, re-verify once it deploys.

## Checklist (confirmed this revision)

| Item | Status | Evidence |
|---|---|---|
| No secrets hardcoded | ✅ | `env_vars` in `main.tf` carry no credential values; `PFI_AUTH_DEMO_ENABLED=true` enables a pre-existing, already-reviewed demo-login code path, it is not a credential itself; JWT signing uses the image's existing demo default (non-production, `SecurityStartupValidator` only enforces a real secret under `production`/`prod` profile, never set here) |
| No JSON keys | ✅ | Deploy uses the operator's own `gcloud`/ADC credentials for `terraform plan` (read-only); no service-account key file created, downloaded, or referenced anywhere in this stage's changes |
| CI pusher SA ≠ runtime SA | ✅ | `pfi-github-artifact-pusher` (CLOUD-03, Artifact Registry write only) is never referenced as a `service_account_email` for any Cloud Run service; Backend uses `pfi-backend-runtime`, Frontend uses the new `pfi-frontend-runtime-dev` |
| Backend endpoints protected | ✅ | Public Cloud Run ingress (`allow_unauthenticated=true`) only grants `roles/run.invoker` (permission to reach the container); `AuthFilter` still requires a Bearer JWT for every path except `/api/system/health` and the 6 listed `/api/auth/*` entry points; `/api/system/diagnostics`/`/warmup` still require ADMIN role — none of this changes by being deployed to Cloud Run |
| Health endpoint minimal/public | ✅ | `/api/system/health` returns only `{"status":"ok"}` — no DB/AI/secrets/infra detail, confirmed in `SystemController#health`'s own javadoc and `SecurityAuthorizationIntegrationTest` |
| Frontend has zero cloud permissions | ✅ | `pfi-frontend-runtime-dev` created with no `project_iam_bindings` entry at all — the `iam` module only grants what's explicitly listed, and nothing lists this account |
| No new AI public exposure | ✅ | `enable_ai_service=false`; `module.ai_cloud_run` not instantiated; `pfi-ai-module` (pre-existing) untouched — its own `allow_unauthenticated` setting is unrelated to and unaffected by this Terraform |
| No DB created | ✅ | `enable_cloud_sql=false`; plan shows zero `google_sql_database_instance` resources; the `backend_runtime → cloudsql.client` IAM grant is also gated off (no IAM change without a corresponding instance) |

## CORS

`PFI_CORS_ALLOWED_ORIGINS` is set to an explicit, exact-match HTTPS origin list (the existing
localhost/Vercel dev defaults plus the predicted `pfi-frontend-dev` Cloud Run URL) — never a
wildcard `*`, matching the same discipline `SecurityStartupValidator.validateCors()` already
enforces for production (not active here, but the same standard is followed voluntarily).

## Demo auth (`PFI_AUTH_DEMO_ENABLED=true`)

This is the one setting in this stage's env vars that changes application behavior beyond
infrastructure wiring: it enables `POST /api/auth/demo-doctor`, an already-existing, already-tested
code path (used identically in the EXT-04A local Docker E2E harness) that issues a real JWT for a
synthetic demo account without requiring a database-backed registration flow. Scoped to this DEV
deployment only; never intended for DEMO/production environments, where `enable_cloud_sql=true`
and a real account flow would apply instead.

## Ingress model

Both Backend and Frontend are `INGRESS_TRAFFIC_ALL` (public internet) with `allow_unauthenticated`
Cloud Run invoker — necessary for a browser to reach either service directly with no VPC/load
balancer in front yet. This is a deliberate DEV-stage simplicity tradeoff (see
`CLOUD_04_CLOUD_RUN_DEV.md` "Networking"), not a security regression: Cloud Run ingress and the
application's own `AuthFilter` JWT gate are two independent layers, and only the outer one changes
here.

## Not covered by this review (future stages)

Vulnerability scanning of the published images (Artifact Registry scanning stays disabled, per
`CLOUD_03_SECURITY_REVIEW.md`), Monitoring/alerting on anomalous access patterns (CLOUD-07), and
Secret Manager-backed credential rotation (CLOUD-05) are all explicitly out of scope for CLOUD-04.
