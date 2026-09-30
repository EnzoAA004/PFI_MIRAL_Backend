# CLOUD-03 — Security Review

## Threat model: what this design prevents

| Risk | Mitigation |
|---|---|
| Leaked long-lived credential | No service-account JSON key exists anywhere — WIF exchanges a short-lived GitHub OIDC token for a short-lived GCP access token, per run, never persisted |
| Fork PR exfiltrating cloud credentials | Publish workflows trigger on `workflow_dispatch` only — GitHub never grants `workflow_dispatch` to non-collaborators, so a fork's PR cannot invoke these workflows at all |
| An unrelated repo (same GitHub owner) impersonating the pusher identity | `attribute_condition` is an explicit 3-repo allowlist (`assertion.repository in [...]`), not `attribute.repository_owner == "EnzoAA004"` — a fourth repo under the same account, even one the owner creates later, is not trusted until explicitly added |
| Pusher identity used to write/read/delete unrelated GCP resources | `roles/artifactregistry.writer` is granted on the `pfi-containers` **repository**, via `google_artifact_registry_repository_iam_member`, never `google_project_iam_member` — the identity has zero project-wide permissions |
| Pusher identity escalating its own or another identity's privileges | No `roles/iam.serviceAccountAdmin`, no `roles/iam.serviceAccountUser` on any other SA, no `roles/owner`/`roles/editor` anywhere in this design |
| `:latest` tag ambiguity / accidental rollback to unknown code | Every push is tagged by `${{ github.sha }}` only; no workflow ever writes `:latest` |
| Workflow requesting broader GitHub permissions than needed | `permissions: contents: read, id-token: write` only, no `packages`, `actions`, or `contents: write` |

## What this design does NOT yet cover (intentionally out of scope for CLOUD-03)

- **Deploying** the pushed image anywhere (Cloud Run) — CLOUD-04's job, with its own IAM design
  (`roles/run.developer` etc.) not granted to this stage's pusher identity.
- **Automatic** publishing on push to a branch — every publish here is a manual `workflow_dispatch`.
  A future automatic DEV pipeline (per CLOUD-00O) would need its own, separately reviewed trust
  condition (e.g., restricted to a specific release/cloud branch ref), not assumed by this design.
- **Vulnerability scanning** of pushed images — `containerscanning.googleapis.com` stays disabled
  (see `CLOUD_03_COST_CHECK.md`); images are pushed unscanned until that's separately authorized.
- **Registry retention/cleanup** — no `terraform apply`'d cleanup policy exists; old SHA-tagged
  images accumulate indefinitely until CLOUD-03's retention design (below) is itself authorized and
  applied.

## API-enablement impact — audited with a real plan, not assumed

A real `terraform plan` against live GCP succeeded (7 to add, 0 change, 0 destroy) **without**
enabling `iam.googleapis.com`, `iamcredentials.googleapis.com`, `sts.googleapis.com`, or
`cloudresourcemanager.googleapis.com` — planning a *new* resource (nothing yet in state) doesn't
require its underlying API to be live, only reads (`data` sources, refresh of existing state)
do, and this plan's only live read was `pfi-containers` (already-enabled `artifactregistry.googleapis.com`).
**`terraform apply` will fail without `iam.googleapis.com` enabled** — that's the API the four
`iam.googleapis.com`-family resources (pool, provider, service account, impersonation bindings)
actually create against. `iamcredentials.googleapis.com` and `sts.googleapis.com` are not called by
Terraform at all in this design — they're invoked later, at OIDC-token-exchange time, by
`google-github-actions/auth` inside a GitHub Actions run. Enabling an API is not itself a
security-sensitive action (it grants no identity anything by itself), but each is still a real,
auditable change to project surface area — see the exact table in `CLOUD_03_CHANGE_PLAN.md`.

## Residual risk / accepted tradeoffs

- WIF pool/provider duplication (`github-pool-dev`/`github-pool-demo`) flagged in the first draft
  is now **resolved**: exactly one pool, one provider, one service account, owned by
  `infra/terraform/platform/identity`, referenced (never recreated) by `environments/dev` and
  `environments/demo` — confirmed via `terraform plan` on both: neither shows any
  `workload_identity`/`artifact_pusher` resource.

## Registry retention policy (designed, NOT applied)

Future policy, for a later authorized stage:

- **Keep**: the last N (e.g. 10) SHA-tagged images per component on `dev`-origin pushes; every
  SHA that was ever promoted to `demo` (tagged release); anything referenced by a currently-running
  Cloud Run revision (once CLOUD-04 exists).
- **Delete**: untagged manifests (build layers with no tag pointing at them) older than a fixed
  window (e.g. 30 days) — `pfi-containers` already has `cleanupPolicyDryRun: true` set (observed
  during the CLOUD-03 audit, likely a repository default rather than something this project
  configured), meaning a dry-run cleanup policy evaluation is possible without deleting anything;
  worth inspecting in a later stage before authorizing a real (non-dry-run) policy.
- Applying an actual `google_artifact_registry_repository` `cleanup_policies` block is **not** part
  of CLOUD-03 — flagged for CLOUD-03-followup or CLOUD-08, whichever comes first, once there's
  enough real push history to size a sensible retention window against.

## Security sign-off status

This is a design review, not a completed audit against a live-applied system — the actual
resources don't exist yet. Once `CLOUD_03_PRE_APPLY_REVIEW` is authorized and applied, a follow-up
pass should re-verify (via read-only `gcloud`/`terraform show` commands) that what was actually
created matches what's designed here exactly.
