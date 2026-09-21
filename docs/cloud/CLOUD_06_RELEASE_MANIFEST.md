# CLOUD-06 — Release Manifest (`pfi-cloud-release.v1`)

A release manifest is a small JSON artifact produced by `cloud-06-validate-dev.yml` and consumed by
`cloud-06-promote-demo.yml` — it is the only thing that authorizes a promotion, and it is never a
database, just a GitHub Actions build artifact (`actions/upload-artifact` /
`actions/download-artifact`, 90-day retention).

## Schema: `pfi-cloud-release.v1`

| Field | Type | Description |
|---|---|---|
| `schemaVersion` | string | Always `"pfi-cloud-release.v1"` — a future breaking change to this shape gets a new version string, never a silent field change. |
| `releaseId` | string | Unique per validation run, e.g. `dev-20260905T120000Z-123456789` (UTC timestamp + GitHub Actions run ID). |
| `gitSha` | string | The commit SHA the validating workflow ran at (not necessarily identical to the image tags below, though they're usually the same value). |
| `sourceBranch` | string | The branch `cloud-06-validate-dev.yml` ran on. |
| `backend.repository` | string | Full Artifact Registry repository path (no tag/digest). |
| `backend.tag` | string | Git-SHA image tag — never `"latest"`. |
| `backend.digest` | string | `sha256:...` digest resolved live from Artifact Registry at validation time (not caller-supplied). |
| `frontend.*` | object | Same three fields as `backend`. |
| `ai` | object or `null` | Same shape as `backend`/`frontend` when the AI image is part of a release; `null` while the AI publish stays deferred (per CLOUD-03). |
| `validatedInDev` | boolean | `true` only if every DEV validation step (image exists, digest matches, Cloud Run DEV actually runs it, smoke test passed) succeeded. `cloud-06-promote-demo.yml` refuses to proceed if this is not `true`. |
| `validatedAt` | string | UTC ISO-8601 timestamp of validation. |

No field ever holds a secret value, a password, or a JWT — this manifest is safe to log, upload as a
public build artifact, and read in a promotion workflow without any redaction.

## Example

```json
{
  "schemaVersion": "pfi-cloud-release.v1",
  "releaseId": "dev-20260905T120000Z-123456789",
  "gitSha": "dee0826c9e1121e348192058acde841b6f933608",
  "sourceBranch": "cloud/cloud-06-promotion-backend",
  "backend": {
    "repository": "us-central1-docker.pkg.dev/pfi-asplanatti-fabrello-v1/pfi-containers/backend",
    "tag": "dee0826c9e1121e348192058acde841b6f933608",
    "digest": "sha256:0000000000000000000000000000000000000000000000000000000000000000"
  },
  "frontend": {
    "repository": "us-central1-docker.pkg.dev/pfi-asplanatti-fabrello-v1/pfi-containers/frontend",
    "tag": "1c77231e2e1833bada6dc1c0f4f622f1d32e3470",
    "digest": "sha256:1111111111111111111111111111111111111111111111111111111111111111"
  },
  "ai": null,
  "validatedInDev": true,
  "validatedAt": "2026-09-05T12:00:00Z"
}
```

(Digests above are placeholders — a real manifest's digest fields always come from a live
`gcloud artifacts docker images describe` call, never typed by hand or predicted.)

## Why a JSON artifact, not a database

No database exists to hold releases, and none is proposed — a GitHub Actions artifact is:
sufficient (a promotion always references the manifest from a specific, identifiable validation run,
via `run-id`), free (no new GCP resource, no new cost), and consistent with this stage's plan-only,
zero-new-cost mandate. If release history ever needs to be queried across many releases (not just
"promote this one specific validated run"), that would be a separate, explicitly scoped future
enhancement — not needed for CLOUD-06's goal of a single, auditable promotion per invocation.
