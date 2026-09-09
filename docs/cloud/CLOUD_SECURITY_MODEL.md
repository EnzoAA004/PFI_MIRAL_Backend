# Cloud Security Model

Design only. No IAM binding, no secret, no service account was created or modified.

## Authentication: GitHub Actions → GCP

**Workload Identity Federation only. No service-account JSON key is ever downloaded or stored in
GitHub Secrets.** Design:

1. A Workload Identity Pool + Provider configured to trust GitHub's OIDC token issuer, scoped to
   this repository (and optionally to specific branches/environments — e.g. only the release
   branch and only the `demo` GitHub Environment may assume the deploy identity).
2. A dedicated CI/CD service account (new — distinct from the four already-existing service
   accounts found in the audit: `pfi-training-vm`, `pfi-ai-runtime`, `pfi-backend-runtime`, default
   Compute SA) granted only the roles needed to push images and deploy Cloud Run revisions:
   `roles/artifactregistry.writer`, `roles/run.developer`, `roles/iam.serviceAccountUser` (to act as
   the runtime service accounts below).
3. GitHub Actions exchanges its OIDC token for a short-lived GCP access token at workflow run time
   — nothing long-lived ever leaves GCP.

## Runtime least privilege, per service

| Service | Needs | Must NOT have |
|---|---|---|
| Frontend (Cloud Run) | Nothing beyond default — it only serves static assets and proxies API calls to the Backend URL via env var | No DB access, no Secret Manager access, no direct AI-service call |
| Backend (Cloud Run) | Cloud SQL client role, Secret Manager accessor (for JWT secret + DB password), ability to call the AI service's Cloud Run URL, `objectAdmin`/`objectViewer` on only the specific Storage bucket(s) it needs | No project-wide IAM, no access to secrets outside its own list |
| AI service (Cloud Run) | Read access to its own model-artifact storage location (if externalized to Cloud Storage instead of baked into the image), nothing else | No DB access, no Secret Manager access beyond what it strictly needs (likely none) |

Each service gets its **own** runtime service account (not the reused existing ones, which appear
tied to training/other work) with exactly this scoped role set — a new decision to make explicit at
CLOUD-01/04 time, not implied by the pre-existing `pfi-backend-runtime`/`pfi-ai-runtime` accounts
without first checking what those already do.

## Secrets inventory and Secret Manager plan

| Secret | Current state (found in audit) | Target |
|---|---|---|
| JWT signing secret (`PFI_AUTH_JWT_SECRET`) | Plain env var, **insecure default** `pfi-demo-change-me-2026` | Secret Manager, generated per environment, never committed |
| Database password (part of `DATABASE_URL`) | Plain env var | Secret Manager, injected into Cloud Run as a mounted secret env var |
| Internal service-to-service credentials (if any auth is added between Backend and AI) | None found today (AI service currently has no auth check) | Secret Manager, if/when internal auth is introduced — not required to be added just for this audit |
| LLM token (EXT-06, if ever unblocked) | N/A — EXT-06 stays blocked | Secret Manager, only if/when a real hosted LLM is authorized |

**No real secret value is ever placed in Terraform state or `.tf` files.** Terraform only
references the Secret Manager secret's *name*/*version*, never its value; the actual secret value
is created out-of-band (`gcloud secrets versions add` run manually or via a separate, tightly
scoped bootstrap step) or generated at deploy time.

## Not implemented by this document

No Workload Identity Pool, no service account, no Secret Manager secret was created. This is the
design CLOUD-01/CLOUD-04/CLOUD-05 implement under their own explicit authorization/cost gates.
