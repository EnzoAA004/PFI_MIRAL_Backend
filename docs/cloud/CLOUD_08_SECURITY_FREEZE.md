# CLOUD-08 — Security Final Review

Findings classified as **BLOCKER** (must fix before further deployment), **WARNING** (should fix,
not deployment-blocking), or **ACCEPTED_RISK** (known, deliberately not fixed, with a stated reason).

| Item | Verified state | Classification |
|---|---|---|
| WIF keyless (no service-account JSON key) | Confirmed live: `gcloud iam service-accounts keys list --managed-by=user` returns 0 for `pfi-github-artifact-pusher` | ✅ OK |
| No JSON keys anywhere else | Not re-audited for the 4 pre-existing SAs this stage — out of scope (this session never touched them) | WARNING — worth a one-time check in a future session, not created by this work |
| CI pusher separation | `pfi-github-artifact-pusher` has exactly one grant: repository-scoped `artifactregistry.writer` on `pfi-containers`. No deploy permission (`run.developer`) | ✅ OK |
| Runtime SA separation | `pfi-backend-runtime`, `pfi-ai-runtime`, `pfi-frontend-runtime-dev` are three distinct identities, none reused across components | ✅ OK |
| Frontend no DB | `pfi-frontend-runtime-dev` has zero IAM role grants — confirmed via `gcloud projects get-iam-policy` filtered for this SA | ✅ OK |
| Frontend no secrets | Same SA, same zero-grants finding — no `secretAccessor` role anywhere for Frontend | ✅ OK |
| Backend least privilege | `pfi-backend-runtime` has `roles/run.invoker` (project-level, pre-existing from before this session) + would gain `roles/cloudsql.client` only once CLOUD-05 applies — no broader role | ✅ OK, with one WARNING below |
| Backend's pre-existing `roles/run.invoker` project-level grant | Found in the CLOUD-03 audit, not created by this session — a project-level (not resource-scoped) invoker grant predates CLOUD-00 | WARNING — worth tightening to resource-level in a future IAM cleanup pass, not blocking, not newly introduced |
| Cloud SQL IAM | Designed (CLOUD-05) as `roles/cloudsql.client` only, granted to `pfi-backend-runtime` alone — not yet applied, so not yet live | N/A until applied — design itself is least-privilege |
| Secret Manager resource-level access | CLOUD-05 design grants `secretAccessor` per-secret (`google_secret_manager_secret_iam_member`), not project-wide | ✅ OK by design (not yet applied) |
| No wildcard CORS | `pfi-backend-dev`'s `PFI_CORS_ALLOWED_ORIGINS` is an explicit origin list, fixed in CLOUD-04 to reference the real resolved Frontend URL via a data source, not a guess or a `*` | ✅ OK, verified live |
| No broad `authorized_networks` on Cloud SQL | CLOUD-05 design uses public IP with an **empty** `authorized_networks` list plus the `cloudsql-proxy` sidecar for authenticated tunneling — not a `0.0.0.0/0` allowlist | ✅ OK by design (not yet applied) — WARNING: public IP at all (vs. private-only) is a deliberate simplicity tradeoff for a thesis project, documented in `CLOUD_05_CLOUD_SQL_DESIGN.md`, not hidden |
| No secrets in Terraform state | Confirmed by design: `modules/secrets` never writes a `google_secret_manager_secret_version` resource, only the container — true for both the already-applied CLOUD-03 state and the not-yet-applied CLOUD-05 design | ✅ OK |
| No patient/PHI in logs | Not independently re-verified this stage (would require live log inspection against real traffic, which doesn't exist yet since DEMO isn't up) — the existing `SensitiveDataLeakRegressionTest` (Backend test suite, confirmed passing in every CLOUD-02 run) covers this at the code level | ACCEPTED_RISK for this freeze — re-verify with real log samples once DEMO/CLOUD-05 actually carries traffic |
| No AI direct exposure | Frontend never calls the AI service directly (architecture-level: only the Backend proxies to AI); AI Cloud Run service itself is designed `allow_unauthenticated=false` in CLOUD-01's original module design | ✅ OK by design — AI service isn't deployed yet at all, so nothing to verify live |
| `roles/run.invoker` project-level (default Compute SA `roles/editor`) | Pre-existing, found in CLOUD-00's original audit, not created or expanded by this session | ACCEPTED_RISK — pre-existing broad grant on the default Compute Engine SA, unrelated to any resource this session created; flagged for awareness, not remediated here since remediating a pre-existing unrelated grant is outside this session's change scope |
| Rollback executor IAM (CLOUD-07) | `cloud-07-rollback.yml` has no identity granted `roles/run.developer` yet — the workflow exists but cannot actually execute a rollback today | WARNING — must be resolved (grant a specific, scoped identity) before this workflow is ever relied upon for a real rollback |
| `containerscanning.googleapis.com` | Confirmed still disabled | ✅ OK — matches every stage's explicit instruction not to enable it |
| `cloudresourcemanager.googleapis.com` | Confirmed still disabled | ✅ OK — never needed for anything applied so far |

## Summary

**0 new BLOCKERs introduced by CLOUD-00 through CLOUD-07.** 2 WARNINGs worth a future pass (the
pre-existing project-level `run.invoker` grant on Backend's runtime SA; the CLOUD-07 rollback
executor's missing IAM grant). 2 ACCEPTED_RISKs, both explicitly reasoned (PHI-in-logs
re-verification pending real traffic; the pre-existing default Compute SA's broad `editor` role,
unrelated to and unchanged by this work).
