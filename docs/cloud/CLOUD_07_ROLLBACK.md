# CLOUD-07 — Rollback

**Status: EXPERIMENTAL / PLAN-ONLY. No traffic has ever been shifted by any CLOUD-07 workflow.**
`cloud-07-rollback.yml` evaluates and records a rollback decision but has no traffic-update step at
all — `execute=true` is accepted as an input but does nothing extra, by design, until a separate,
explicitly authorized change adds a real execute path.

## Phase 0 audit — current DEV revision state (read-only)

| Service | Revisions | Traffic |
|---|---|---|
| `pfi-backend-dev` | `pfi-backend-dev-00001-6qw` (Ready), `pfi-backend-dev-00002-jwd` (Ready) | 100% → `00002-jwd` (latest) |
| `pfi-frontend-dev` | `pfi-frontend-dev-00001-c7v` (Ready) | 100% → `00001-c7v` (latest, only revision) |

Backend already has a real prior-good revision (`00001-6qw`) still addressable — a live example of
exactly what a rollback target looks like, confirming the "Cloud Run never deletes a revision on
its own" assumption this whole design depends on. Frontend has only ever had one revision (no
rollback target exists for it yet).

## Rollback contract: `pfi-cloud-rollback.v1`

| Field | Type | Description |
|---|---|---|
| `schemaVersion` | string | Always `"pfi-cloud-rollback.v1"`. |
| `triggeredAt` | string | UTC ISO-8601 timestamp. |
| `environment` | string | `dev` or `demo`. |
| `service` | string | Cloud Run service name. |
| `currentRevision` | string | The revision serving traffic at evaluation time. |
| `candidateRevision` | string | The (possibly bad) revision being evaluated — usually equal to `currentRevision` when evaluating "should we roll back what's live right now". |
| `stableRevision` | string | The last known-good revision a rollback would target. |
| `reason` | string | Free-text trigger reason (e.g. `"5xx rate 12% for 6 minutes"`, `"manual: reported broken login"`). |
| `revisionExists` | boolean | Both `candidateRevision` and `stableRevision` confirmed to exist and belong to `service`. |
| `healthOk` | boolean | Candidate's own health check result. |
| `executeRequested` | boolean | Whether the workflow run was invoked with `execute=true`. |
| `executed` | boolean | Always `false` in CLOUD-07 — no execute path exists yet. |

No field ever holds a secret. Uploaded as a GitHub Actions artifact (`pfi-cloud-rollback-record`,
90-day retention), same pattern as `pfi-cloud-release.v1` (`CLOUD_06_RELEASE_MANIFEST.md`).

## Phase 2 — Rollback policy: when to roll back

| Trigger | Automatic or manual | Rationale |
|---|---|---|
| Health check failing | Automatic | Direct evidence the service can't serve at all. |
| Frontend smoke fail (root `/` or `/env.js` wrong) | Automatic | User-facing breakage, not a warning. |
| Auth fail (demo-doctor login or protected-endpoint call rejected) | Automatic | Core functionality broken — nothing else in the app works without auth. |
| CORS fail (preflight rejected) | Automatic | Same category as CLOUD-04's real CORS incident — a silent, user-visible breakage if left running. |
| Digest mismatch (running image ≠ validated image) | Automatic | Means something deployed outside the validated pipeline, or a race between validate and promote — never continue on an unverified image. |
| 5xx error rate above threshold | Automatic | Signal A/B from `CLOUD_07_ALERTING.md` — sustained, not a single blip (5-minute window). |
| Startup crash / container fails to become Ready | Automatic | Cloud Run itself won't route traffic to a revision that never becomes Ready, but if it's flapping (Ready then crashing under load), that's still a rollback trigger. |
| Permission/runtime failure (e.g. `SecurityStartupValidator` fail-closed, missing secret at boot) | Automatic | Same category as startup crash — the revision cannot function correctly. |
| A single transient 5xx, one slow request, or a log-level `WARN` | **Manual only — no automatic rollback** | Noise at this project's traffic volume; an automatic policy this sensitive would roll back constantly and erode trust in the signal. A human decides whether a pattern of warnings warrants a manual rollback. |

"Automatic" here means "the decision engine (`rollback-decision.sh`) returns `ROLLBACK_REQUIRED`",
not "traffic is actually shifted automatically" — CLOUD-07 has no automatic execute path at all
(every rollback, once a real execute step exists in a later stage, still requires a human to run the
workflow with `execute=true`). This document draws the automatic/manual line at the *decision*, since
that's what CLOUD-07 actually implements; wiring a fully automatic execute-on-decision pipeline is a
distinct, larger trust decision for a future stage, not assumed here.

## Why traffic-shift, not redeploy

Unchanged from the CLOUD-06 design (`CLOUD_06_ROLLBACK_DESIGN.md`): Cloud Run keeps every revision
addressable until explicitly deleted, so a rollback is `gcloud run services update-traffic <service>
--to-revisions=<revision>=100` — a routing change, not a new deploy, not a Terraform apply, not a
rebuild.

## `cloud-07-rollback.yml`

Generalizes CLOUD-06's `cloud-06-rollback-demo.yml` (DEMO-only, pure dry-run print) into a real
decision-making workflow usable for either environment:

1. Rejects `latest` as a revision name outright.
2. Authenticates read-only (proposed `GCP_CI_READER_SERVICE_ACCOUNT`, same identity CLOUD-06
   proposed — not yet created).
3. Runs `scripts/cloud/verify-cloudrun-revision.sh` (Phase 4) — confirms both revisions are real,
   belong to the named service, and are distinct.
4. Checks the candidate's own health endpoint directly (read-only `curl`).
5. Builds and uploads a `pfi-cloud-rollback.v1` record.
6. Runs `scripts/cloud/rollback-decision.sh` (Phase 14's decision engine) and prints
   `ROLLBACK_REQUIRED` or `NO_ROLLBACK`.
7. Prints (never executes) the exact `gcloud run services update-traffic` command.

`execute=true` is accepted as a workflow input specifically so its *absence of effect* is visible and
testable now — a future authorized change adds the real traffic-update step gated behind it, rather
than introducing a new input at that time.

## Release → rollback linkage (Phase 13)

Every revision that ever received traffic via `cloud-06-promote-demo.yml` is traceable back to the
`pfi-cloud-release.v1` manifest that validated it (same `releaseId`/digest), and every rollback
evaluation produces a `pfi-cloud-rollback.v1` record naming the exact revision being evaluated and
the stable revision it would fall back to. Chaining them: `release manifest → promoted revision (a
GitHub Actions run + a live Cloud Run revision) → rollback record (if ever evaluated) → the prior
revision, which is itself traceable to an earlier release manifest`. No database is needed for this
chain — `revisionName` and `imageDigest` are already globally unique identifiers Cloud Run and
Artifact Registry both expose for free; a manifest/record pair is looked up by revision name via
`gcloud run revisions describe`, not a foreign key into anything CLOUD-06/07 built.
