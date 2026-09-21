# CLOUD-06 — Rollback Design (DEMO)

**Design-only. `cloud-06-rollback-demo.yml` is a dry-run: it prints the exact rollback command and
confirms the target revision exists, but never shifts traffic.** There is nothing to roll back yet —
DEMO does not exist. This document describes the strategy for when it does.

## Why traffic-shift, not redeploy

Cloud Run keeps every revision of a service addressable (until explicitly deleted), each with its own
immutable image reference. A rollback is a **traffic reassignment** between two already-existing
revisions — `gcloud run services update-traffic <service> --to-revisions=<revision>=100` — not a new
deploy, not a rebuild, and not a Terraform apply. This is faster (seconds, no container startup wait
beyond what's already warm) and safer (no risk of the rollback itself failing to build or pull an
image) than redeploying the prior image tag.

## Flow

```
revision N (100% traffic, known-good)
   → deploy revision N+1 (new release, also takes 100% traffic — no canary/gradual
     rollout configured in this project's Cloud Run setup)
   → post-deploy smoke (cloud-06-smoke.sh against the DEMO URLs)
   → smoke fails → gcloud run services update-traffic <service> --to-revisions=N=100
   → smoke passes → N+1 stays serving, N remains addressable but receives no traffic
```

No gradual/canary rollout (e.g. 10% → 100%) is configured for DEMO — a demo environment's traffic
volume and audience (typically one live viewer/session) doesn't benefit from a staged rollout, and
Cloud Run's default (100% to the newest ready revision) is simpler to reason about for this project's
scale. Revisit if DEMO is ever used for something with real concurrent, varied traffic.

## `cloud-06-rollback-demo.yml` (dry-run today)

Inputs: `service_name` (e.g. `pfi-backend-demo`), `target_revision` (the last known-good revision
name). Steps:

1. Authenticate read-only (proposed `GCP_CI_READER_SERVICE_ACCOUNT` — see
   `CLOUD_06_SECURITY_REVIEW.md`).
2. `gcloud run revisions describe <target_revision>` — confirms the revision exists and is in a
   `Ready`/serving-capable state before proposing to route traffic to it. Read-only.
3. Print (never execute) the exact `gcloud run services update-traffic` command.

Turning this into a real rollback is a **separate, explicitly authorized change** to the workflow
file — adding the actual `gcloud run services update-traffic` execution step — made only once DEMO is
real and this design has been reviewed against an actual multi-revision service.

## Failure-triggered rollback (once real)

Per `CLOUD_06_DEV_TO_DEMO_PROMOTION.md`'s failure policy: a post-deploy smoke failure is the trigger
condition for invoking this rollback, either manually (an operator runs the workflow with the last
known-good revision name) or, in a later stage, automatically as a follow-up step in
`cloud-06-promote-demo.yml` once that workflow gains a real apply step. CLOUD-06 does not wire
automatic rollback-on-smoke-failure yet, since there is no real apply/deploy step to fail after.

## Data loss consideration

Rolling back Cloud Run traffic never touches Cloud SQL — if DEMO ever has `enable_cloud_sql=true` and
a schema migration shipped in N+1 is incompatible with N's code, a traffic rollback alone would not
undo the migration. This is a known limitation of traffic-only rollback for any stateful backing
store; not a new gap CLOUD-06 introduces, but worth stating: a migration-incompatible rollback would
require its own, separately designed procedure (out of scope while DEMO has no Cloud SQL instance).
