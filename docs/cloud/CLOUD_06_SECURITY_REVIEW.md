# CLOUD-06 — Security Review

## Permissions actually used this stage

**None new.** All read-only audit calls this stage (`gcloud sql instances list`-style checks,
`tiers.list`, GitHub REST API reads, `terraform plan` against real state) used the operator's own
existing `gcloud`/`git credential` authentication — no workflow ran, no WIF exchange happened, no new
IAM binding was created or used.

## Proposed identities for the CLOUD-06 workflows (NOT created this stage)

The three new workflow files reference two identities that do not exist yet. Creating them is an IAM
write, explicitly prohibited this stage — they are documented here as a concrete proposal for a
future, separately authorized step, not created now.

### 1. `pfi-github-ci-reader` (proposed) — for `cloud-06-validate-dev.yml` and `cloud-06-rollback-demo.yml`

| Property | Value |
|---|---|
| Purpose | Read-only Cloud Run/Artifact Registry state checks from CI (digest resolution, `gcloud run services describe`, `gcloud run revisions describe`) |
| Proposed roles | `roles/run.viewer` (read Cloud Run service/revision state — cannot deploy or update traffic), `roles/artifactregistry.reader` (read image digests — cannot push) |
| Why not reuse `pfi-github-artifact-pusher` | That identity already holds `roles/artifactregistry.writer` (can push) — reusing it for read-only CI steps would violate least privilege by handing a read-only workflow a write-capable identity it doesn't need. A distinct, narrower identity keeps a compromised or misused validate/rollback workflow run from being able to push a malicious image, even in principle. |
| Referenced as | Repo variable `GCP_CI_READER_SERVICE_ACCOUNT` (not yet created) |

### 2. A Terraform-plan identity (proposed) — for `cloud-06-promote-demo.yml`'s `terraform plan` step

| Property | Value |
|---|---|
| Purpose | Read state of every resource type `environments/demo` declares, to compute a plan, without being able to create/modify/delete any of them |
| Proposed roles | `roles/run.viewer`, `roles/iam.serviceAccountViewer`, `roles/artifactregistry.reader`, `roles/cloudsql.viewer` (all viewer-only — no `Editor`, no `Owner`, no `*.admin`) |
| Why a separate identity from `pfi-github-ci-reader` | `terraform plan` needs to read a broader resource surface (IAM, Cloud SQL, Storage bucket metadata) than the narrower validate-dev/rollback checks — scoping it separately means a validate-dev compromise doesn't also expose Terraform-plan-level read access, and vice versa. |
| Referenced as | Not yet named as a repo variable — `cloud-06-promote-demo.yml`'s Terraform steps currently reuse the same auth step as the CI-reader identity for design purposes; a real implementation would split this out before ever being run for real. |

Both proposals stay **viewer-only** — neither can create, update, or delete any GCP resource. Neither
is `Editor` or `Owner`. Neither is a JSON key — both would authenticate via the same
`google-github-actions/auth@v2` + WIF pattern already established in `cloud-03-publish-image.yml`.

## Workflow-level permissions (`permissions:` block, GitHub Actions token)

All three new workflows declare:

```yaml
permissions:
  contents: read
  id-token: write
  actions: read   # validate-dev, promote-demo only — read own artifacts
```

**No `contents: write`** in any of them — none of these workflows commits, tags, or pushes to the
repository. `id-token: write` is the minimum required for the OIDC-to-WIF exchange; no other
permission is elevated.

## Cloud SQL / Secret Manager permission scope

No new IAM binding for Cloud SQL or Secret Manager is proposed or created this stage —
`enable_cloud_sql`/`enable_secrets` stay `false` for DEMO exactly as they do for DEV pre-CLOUD-05-
apply. The DEMO Terraform's existing `project_iam_bindings = var.enable_cloud_sql ? [...] : []` gating
(fixed this stage, see `CLOUD_06_CHANGE_PLAN.md`) already ensures no `roles/cloudsql.client` grant
appears in any plan while the flag is off, mirroring DEV's design.

## No JSON keys, no Owner/Editor — anywhere in this design

Consistent with every prior CLOUD stage: every proposed and existing identity in this design
authenticates via Workload Identity Federation (OIDC token exchange), never a downloaded
service-account key; every role proposed above is a narrow, resource-scoped viewer/reader role, never
`roles/editor` or `roles/owner`.

## Verdict

No security finding blocks this design. The two proposed new identities are the only IAM change this
stage recommends for a future authorization — both viewer-only, both narrower in scope than the
existing artifact-pusher identity, and neither created here.
