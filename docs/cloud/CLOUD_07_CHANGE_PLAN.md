# CLOUD-07 — Change Plan

## Terraform module: `modules/monitoring`, completed

Replaced the CLOUD-01 skeleton's single `enabled` flag + one hardcoded 5xx policy with three
granular flags (`enable_alerts`, `enable_uptime_checks`, `enable_notification_channels`, all default
`false`) and four resources: `google_monitoring_alert_policy.backend_5xx`,
`google_monitoring_alert_policy.frontend_5xx`, `google_monitoring_uptime_check_config.backend_health`,
`google_monitoring_alert_policy.backend_uptime_failure`, plus an optional
`google_monitoring_notification_channel.email` (created only when a real address is supplied, never
by default). Both `environments/dev` and `environments/demo` updated to the new module interface —
`enable_monitoring` (the existing environment-level variable) drives all three granular flags
together for this stage's plan preview.

## Plan 1 — `enable_monitoring=false` (default, current live DEV state)

```
No changes. Your infrastructure matches the configuration.
```

**Result: 0 to add, 0 to change, 0 to destroy.** Confirms the monitoring module rewrite is fully
inert while the flag stays at its default — no drift on Backend, Frontend, or any other resource.

## Plan 2 — `enable_monitoring=true` (the CLOUD-07 monitoring preview)

Full raw output: `CLOUD_07_TERRAFORM_PLAN_MONITORING_OUTPUT.txt`.

**Plan: 4 to add, 0 to change, 0 to destroy.**

| Resource | Action |
|---|---|
| `module.monitoring.google_monitoring_alert_policy.backend_5xx[0]` | + create |
| `module.monitoring.google_monitoring_alert_policy.frontend_5xx[0]` | + create |
| `module.monitoring.google_monitoring_uptime_check_config.backend_health[0]` | + create |
| `module.monitoring.google_monitoring_alert_policy.backend_uptime_failure[0]` | + create |

No `google_monitoring_notification_channel` appears (empty `notification_channel_email` in both
environments — matches the module's own documented behavior). **Confirmed absent** (matches Phase
11's explicit STOP gate — none of these appear): Cloud SQL, Secret Manager, new Cloud Run service, any
IAM binding unrelated to monitoring, Artifact Registry, WIF, Storage replacement, AI service. No
`~ update` and no `- destroy` line anywhere — no drift, no replace, no unrelated change.

## Scripts and workflows validated (Phase 13/14, no cloud writes)

| Artifact | Validation performed | Result |
|---|---|---|
| `cloud-07-rollback.yml` | Parsed with PyYAML, `jobs` structure confirmed | OK, 1 job |
| `scripts/cloud/verify-cloudrun-revision.sh` | `bash -n` + 5 synthetic scenarios against a mocked `gcloud` (both revisions exist/distinct → pass; candidate missing → fail; stable missing → fail; candidate == stable → fail; `latest` → rejected) | All 5 behaved as designed |
| `scripts/cloud/rollback-decision.sh` | `bash -n` + Phase 14's 5 required failure simulations (candidate health fail, digest mismatch, candidate/stable revision missing, error-rate threshold exceeded, all-healthy) | All 5 produced the correct `ROLLBACK_REQUIRED`/`NO_ROLLBACK` verdict |

`actionlint`/`shellcheck` remain unavailable on this machine and were not installed, same as
CLOUD-06 — PyYAML parsing plus manual review and the synthetic dry-runs above cover this design pass;
a full `actionlint` pass is a reasonable follow-up before any of these workflows is ever run for real.

## Git status

Branch `cloud/cloud-07-observability-rollback` created from CLOUD-06's final pushed HEAD
(`d05c85a96ee19529594b47ee35978518ec5b8c6a`), per explicit instruction. All work is Backend-repo
Terraform + one new GitHub Actions workflow + two new shell scripts + docs — no Frontend or AI change
needed, so no Frontend/AI branch was created. Nothing committed yet — left as `READY_TO_COMMIT`
pending explicit authorization.
