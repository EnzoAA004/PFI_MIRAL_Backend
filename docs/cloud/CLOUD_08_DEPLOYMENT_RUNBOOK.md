# CLOUD-08 — Deployment Runbook (CLOUD-05 / CLOUD-06 / CLOUD-07)

**Nothing in this document has been executed.** It is the exact, ordered procedure a future
authorized session should follow to actually apply CLOUD-05, then CLOUD-06, then CLOUD-07 — each
gated by its own explicit approval, none assumed by writing this runbook.

## Final deployment order (minimizes risk and cost)

```
0. Preflight (this document's checklist)
1. Enable required APIs (sqladmin.googleapis.com, secretmanager.googleapis.com,
   monitoring.googleapis.com if not already — monitoring.googleapis.com is already enabled)
2. Create Secret Manager containers (empty, no value yet)
3. Upload secret versions out-of-band (gcloud secrets versions add, run manually, never in CI/Terraform)
4. Create Cloud SQL instance (db-f1-micro, per CLOUD_05_CLOUD_SQL_DESIGN.md)
5. Grant minimum IAM (roles/cloudsql.client to backend_runtime, secret accessor bindings)
6. Apply Backend Cloud Run revision with persistence wiring (cloudsql-proxy sidecar, DATABASE_URL,
   PFI_AUTH_JWT_SECRET from Secret Manager)
7. Persistence smoke test (create → restart → confirm survives)
8. Storage integration check (if/when needed — not required by CLOUD-05's current scope)
9. Apply DEMO Cloud Run (Backend + Frontend, same images as DEV)
10. DEV → DEMO promotion validation (digest match, smoke)
11. Apply Monitoring/alerts (CLOUD-07)
12. Final E2E (see CLOUD_08_FINAL_E2E_PLAN.md)
13. Rollback drill (prove the procedure works before relying on it)
14. Freeze (update CLOUD_08_FREEZE_MANIFEST.md with real deployed state)
```

Each numbered step below is gated — see "Hard gates" for the explicit approval required before
starting that step.

## Hard gates

| Gate | Before step | What must be explicitly approved |
|---|---|---|
| **A — Cost approval** | 1 | The ~$9.37/month Cloud SQL run-rate (CLOUD_05_COST_CHECK.md) and its always-on nature (no scale-to-zero) |
| **B — API enable approval** | 1 | Enabling `sqladmin.googleapis.com` and `secretmanager.googleapis.com` specifically (not "APIs in general") |
| **C — Cloud SQL apply approval** | 4 | The exact `terraform plan` for `enable_cloud_sql=true` (7 to add, 1 to change — see CLOUD_05_CHANGE_PLAN.md) reviewed fresh, not reused from an earlier session |
| **D — Secret creation approval** | 2–3 | Which two secrets (`pfi-dev-database-url`, `pfi-dev-jwt-secret`), confirmation no real value is ever written by Terraform |
| **E — Backend revision approval** | 6 | The Backend Cloud Run diff (new sidecar container, env var changes) reviewed as a live `terraform plan`, not the stale one from CLOUD-05's design pass |
| **F — DEMO creation approval** | 9 | The DEMO `terraform plan` (5 to add — see CLOUD_06_CHANGE_PLAN.md) reviewed fresh; confirmation both images are the exact DEV-validated digests, no rebuild |
| **G — Monitoring cost approval** | 11 | The ~$2.63/month uptime-check run-rate (CLOUD_07_COST_CHECK.md) and the multi-region uptime-check caveat resolved or explicitly accepted |
| **H — Final E2E approval** | 12 | Sign-off to run the full E2E plan against live DEMO (not a dry run) |

No step proceeds automatically past its gate. A future session picking this up must re-obtain each
approval explicitly — approvals from this freeze do not carry forward as blanket authorization.

## Predeploy checklist (run before ANY step above)

- [ ] `git status` clean on the branch about to be applied
- [ ] Correct branch checked out (matches the SHA table in `CLOUD_08_FREEZE_MANIFEST.md`)
- [ ] SHAs re-verified against `origin` (`git fetch` + compare, not assumed from memory)
- [ ] Images exist in Artifact Registry (`gcloud artifacts docker images list`)
- [ ] Digests re-verified match what's recorded in the freeze manifest
- [ ] `terraform fmt -check -recursive` clean
- [ ] `terraform validate` clean
- [ ] `terraform plan` re-run **fresh** (never reuse a plan file from a prior session — GCP state may
      have changed) and reviewed line-by-line
- [ ] `destroy == 0`, no unexpected `change`, no `replace` anywhere in the fresh plan
- [ ] Cost re-confirmed against the fresh plan's actual resource shapes, not assumed from this doc
- [ ] IAM bindings in the plan reviewed individually — no project-wide role, no Owner/Editor
- [ ] Secrets strategy reviewed (containers only from Terraform, values only via `gcloud secrets
      versions add` run manually, never committed, never in CI logs)
- [ ] Rollback procedure for this specific step re-read (see per-stage runbook below)
- [ ] Smoke test plan for this specific step ready to run immediately after apply

## CLOUD-05 runbook — Cloud SQL + Secrets + persistence (NOT executed)

**Exact Terraform vars**: `enable_cloud_sql=true`, `enable_secrets=true` (both default `false` today).

**Expected plan shape** (from the real plan already obtained, re-verify fresh before applying):
`7 to add, 1 to change, 0 to destroy` — `google_sql_database_instance.this[0]` (`pfi-postgres-dev`,
`db-f1-micro`, `POSTGRES_15`, ZONAL, 10GB PD_SSD), `google_sql_database.app[0]` (`pfi` db), 2 Secret
Manager secret containers, 1 `cloudsql.client` IAM binding, 2 secret-accessor IAM bindings, and 1
in-place update to `pfi-backend-dev` (adds the `cloudsql-proxy` sidecar container + swaps
`PFI_PERSISTENCE_MODE` to `postgres` + wires the two secret-sourced env vars). **Image digest does
not change** — same revision-creating config change, no rebuild.

**Secret out-of-band steps** (never in Terraform, never in CI):
```bash
# Run manually, by a human, after the secret containers exist:
gcloud secrets versions add pfi-dev-database-url --data-file=- <<< "postgresql://..."
gcloud secrets versions add pfi-dev-jwt-secret --data-file=- <<< "$(openssl rand -base64 32)"
```

**DB username strategy**: application connects via the `cloudsql-proxy` sidecar over
`localhost:5432` (Unix domain socket alternative also viable) — no direct public-IP DB credential
ever reaches the Backend container's own env in plaintext beyond what Secret Manager already
protects.

**Connection string strategy**: `DATABASE_URL` secret value points at `localhost:5432` (through the
sidecar), not the instance's public IP directly — the sidecar handles the actual authenticated
tunnel to Cloud SQL.

**Startup order**: `cloudsql-proxy` container's `depends_on` relationship (already wired in
CLOUD-05's Terraform) ensures the proxy is healthy (TCP probe on 5432) before the `app` container
that needs it is considered ready.

**Smoke test**: create one entity via the API → confirm a 200/201 response → query it back → confirm
data matches.

**Restart persistence test**: after the smoke-created entity exists, force a new revision (e.g. a
trivial env var touch) → confirm the entity is still readable after the new revision serves traffic
— proves persistence survives a container restart, not just an in-memory illusion.

**Rollback/backout**: `terraform apply` with `enable_cloud_sql=false` reverts the Backend Cloud Run
config (drops the sidecar, reverts env vars) but **does not delete the Cloud SQL instance** — that
needs an explicit, separate `enable_cloud_sql=false` PLUS confirming `deletion_protection` is
deliberately turned off first (currently `true` by module default, intentionally).

**Stop/delete strategy**: `gcloud sql instances patch pfi-postgres-dev --activation-policy=NEVER`
stops (not deletes) the instance, dropping compute cost to $0 while keeping the 10GB storage
($1.70/month) and the data. Full deletion is a separate, explicit, harder-to-reverse action — not
bundled into any "stop" step above.

## CLOUD-06 runbook — DEV → DEMO promotion (NOT executed)

**Release manifest generation**: `scripts/cloud/verify-image-digest.sh` confirms the exact digest
being promoted matches what's recorded for the DEV-validated SHA — refuses to proceed on any
mismatch or on a `:latest`-style tag.

**DEV validation**: `cloud-06-validate-dev.yml` (workflow, not yet run) re-confirms DEV is healthy
immediately before promotion — a promotion should never start from an unverified DEV state.

**Approval**: Gate F above — human sign-off on the fresh DEMO plan.

**DEMO plan**: `5 to add, 0 to change, 0 to destroy` (re-verify fresh) — `pfi-backend-demo`,
`pfi-frontend-demo`, their public-invoker bindings, and the DEMO `frontend_runtime` service account.

**Same-image check**: enforced by `verify-image-digest.sh` — DEMO's `terraform.tfvars` must
reference the byte-identical digest already running in DEV, never a fresh build.

**Apply step placeholder**: `cloud-06-promote-demo.yml` (workflow, not yet run) — would run
`terraform apply` against `environments/demo` with the verified image vars.

**Post-deploy smoke**: `scripts/cloud/cloud-06-smoke.sh` — the same health/reachability checks
CLOUD-04 ran against DEV, pointed at the new DEMO URLs.

**Rollback path**: `cloud-06-rollback-demo.yml` (workflow, not yet run) — `terraform destroy
-target=` the two DEMO Cloud Run services (not the whole `demo` state) if promotion smoke fails,
returning DEMO to "does not exist" rather than leaving a broken half-state.

## CLOUD-07 runbook — Monitoring + rollback (NOT executed)

**Monitoring flags**: `enable_monitoring=true` on `environments/dev` and/or `environments/demo`
(independently — each environment's flag is separate).

**Expected resources**: `4 to add` per environment — Backend 5xx alert, Frontend 5xx alert, Backend
uptime check, Backend uptime-check-failure alert. No notification channel unless an email is
supplied (empty by default).

**Cost estimate**: ~$2.63/month per environment for the uptime check (300s interval); alert
policies themselves are $0 (built-in metrics). **Unresolved caveat**: uptime checks run
multi-region by default unless `selected_regions` is set — resolve this (either accept the
higher effective check count's cost, still small, or explicitly restrict regions) before Gate G.

**Notification channel setup**: supply a real email via the `notification_channel_email` variable
— optional, $0 either way.

**Alert policy activation**: automatic once `enable_alerts=true` (bundled under
`enable_monitoring=true` for this stage's environment-level flag).

**Uptime checks**: automatic once `enable_uptime_checks=true` (same bundling).

**Rollback executor IAM**: `cloud-07-rollback.yml` (workflow, not yet run) needs `roles/run.developer`
on whatever identity executes it — **not yet granted to any identity**, since no rollback executor
has been authorized. This is an explicit gap to close before this workflow is ever run for real,
not an oversight to silently patch now.

**Smoke/failure validation**: `scripts/cloud/verify-cloudrun-revision.sh` and
`scripts/cloud/rollback-decision.sh` — both already validated via 5 synthetic dry-run scenarios each
(see `CLOUD_07_CHANGE_PLAN.md`), never against a real failure.
