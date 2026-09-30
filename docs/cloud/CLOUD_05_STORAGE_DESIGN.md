# CLOUD-05 — Storage Design

## Existing bucket audit

`pfi-rm-lumbar-artifacts-2026-ef` — **EXISTING_EXTERNAL**, confirmed present via
`data.google_storage_bucket.existing` (already wired in `modules/storage`, `create_bucket = false`
since CLOUD-01). No new bucket is created by this stage or proposed for creation. Classification is
unchanged from the CLOUD-00 audit: manually created, pre-existing, not currently Terraform-managed
beyond the read-only data-source lookup used to expose its name/URL to other modules.

## Current usage

Not yet wired to any application code path (Backend, Frontend, or AI) as a read/write target — the
bucket exists but nothing in this stage's scope reads or writes to it. No IAM grant beyond what
already exists today is added by CLOUD-05.

## Future usage design (not implemented this stage)

When model checkpoints, inference outputs, or exported reports need object storage (a later stage,
not CLOUD-05), the design should follow the same least-privilege pattern already established for
Secret Manager: a resource-scoped `google_storage_bucket_iam_member` (never a project-wide `roles/
storage.admin`), granted only to the specific runtime service account(s) that need it, with the
minimum role the use case requires (`roles/storage.objectViewer` for read-only consumption,
`roles/storage.objectCreator` for write-only upload paths, avoiding `roles/storage.objectAdmin`
unless delete/overwrite is a genuine requirement).

## Explicitly not done this stage

- No dataset upload. No real patient/clinical data enters this bucket via this stage's work.
- No checkpoint upload without a demonstrated, concrete need.
- No IAM grant on the bucket to any service account (Backend, Frontend, or AI) — none is requested by
  any code path today, so none is added speculatively.
- No new bucket created — the classification above stands: `EXISTING_EXTERNAL`, audited, not
  recreated, not imported into fresh Terraform management beyond the existing read-only lookup.
