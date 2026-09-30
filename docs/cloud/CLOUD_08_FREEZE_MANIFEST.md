# pfi-cloud-freeze.v1

**No secret value appears anywhere in this document.** This is the authoritative snapshot of every
repo/branch/image/deploy state as of this freeze point (2026-09-05). Read this file first before
any future deploy session — it's the ground truth for "what is real right now."

## Repositories

| Repo | Branch (frozen) | Commit SHA | Pushed |
|---|---|---|---|
| Backend | `cloud/cloud-03-artifact-wif-backend` | `dee0826c9e1121e348192058acde841b6f933608` | ✅ |
| Backend | `cloud/cloud-04-cloudrun-backend` | `8ac7c0f50853cd458ad2d5a4756a8313587cf008` | ✅ |
| Backend | `cloud/cloud-05-managed-persistence` | `b456333890902f41e9579c23216fb1c39f53886d` | ✅ |
| Backend | `cloud/cloud-06-promotion-backend` | `d05c85a96ee19529594b47ee35978518ec5b8c6a` | ✅ |
| Backend | `cloud/cloud-07-observability-rollback` | `1da60ca2ba7a52352c4bf71aaaaa32e075141c4e` | ✅ |
| Backend | `cloud/cloud-08-final-freeze` | (this branch, uncommitted at time of writing) | ❌ not yet |
| Frontend | `cloud/cloud-03-artifact-wif-frontend` | `1c77231e2e1833bada6dc1c0f4f622f1d32e3470` | ✅ |
| AI | `cloud/cloud-03-artifact-wif-ai` | `95f1ca1828fef3f49f6e05bd0103d00820525452` | ✅ |
| Backend | `main` (frozen, untouched) | `646f4a123e77f9572fa0c5c4ede0990b1535945d` | ✅ |
| Frontend | `main` (frozen, untouched) | `86991d7e391fd9ef964bc74c4b05c434e96225cb` | ✅ |
| AI | `main` (frozen, untouched) | `90f4a8e076cc18efa9ebce10c604e1c3d1f3e35c` | ✅ |

No Frontend or AI branch exists for CLOUD-04/05/06/07 — none of those stages touched those repos
(confirmed: CLOUD-04 added a Frontend runtime SA and Frontend Cloud Run service via **Backend-repo
Terraform only**; CLOUD-05/06/07 are Backend-repo Terraform + GitHub Actions + docs only).

## Container images (immutable, SHA-tagged, no `:latest` ever used)

| Component | Tag | Registry digest (manifest index) | Deployed digest (platform-specific manifest, live in Cloud Run) | Pushed |
|---|---|---|---|---|
| Backend | `backend:dee0826c9e1121e348192058acde841b6f933608` | `sha256:ae4b44c67b83e0df20c3bf3eb49672f5b958139620715307223ee280cdc88b7c` | `sha256:75106a69465d82f5733afe9fe0bb2392dbc1f154687ac535ec864c6e80b85e24` | 2026-09-04 19:21 UTC |
| Frontend | `frontend:1c77231e2e1833bada6dc1c0f4f622f1d32e3470` | `sha256:81e5f6614e24484c2cb8d02420ff7c9e9c77a5196d549e278c1f0aa9c5237e95` | `sha256:b4136090466130ca2921afc4fbf3e12f025278c43ea43b3225d44b521865e862` | 2026-09-04 19:25 UTC |
| AI | not pushed | — | — | ❌ `AI_IMAGE_PUSH_PENDING_APPROVAL` |

Both tags resolve to exactly 3 manifests each (a platform image manifest, an attestation manifest,
and the tagged manifest-list index) — the standard Buildx multi-manifest output of a single push
event, confirmed by all 3 sharing the same create timestamp (to the second) and no other push event
existing anywhere in the registry's history for these tags. **Immutability confirmed: neither tag
has ever been overwritten.**

## Terraform stage status

| Stage | Design | Code | Plan obtained | Applied | Live in GCP |
|---|---|---|---|---|---|
| CLOUD-01 (foundation) | ✅ | ✅ | ✅ (2026-09-04, ADC-blocked reasoning superseded by later real plans) | partial (see CLOUD-03/04) | modules exist, not directly "applied" as a unit |
| CLOUD-03 (WIF + Artifact Registry IAM) | ✅ | ✅ | ✅ real plan, 7/0/0 | ✅ **applied** | ✅ pool/provider/SA/bindings live |
| CLOUD-04 (Cloud Run DEV) | ✅ | ✅ | ✅ real plan, 5/0/0 (+2 follow-up fixes) | ✅ **applied** (with 2 authorized follow-ups: `cpu_idle` fix, CORS fix) | ✅ `pfi-backend-dev` + `pfi-frontend-dev` live |
| CLOUD-05 (Cloud SQL + Secrets) | ✅ | ✅ | ✅ real plan, 7/1/0 | ❌ **NOT applied** | ❌ 0 Cloud SQL instances, 0 secrets |
| CLOUD-06 (DEV→DEMO promotion) | ✅ | ✅ | ✅ real plan (demo), 5/0/0 | ❌ **NOT applied** | ❌ 0 DEMO Cloud Run services |
| CLOUD-07 (Monitoring + rollback) | ✅ | ✅ | ✅ real plan, 4/0/0 | ❌ **NOT applied** | ❌ 0 alert policies, 0 uptime checks |
| CLOUD-08 (this freeze) | ✅ (this document set) | N/A (docs only) | N/A | N/A | N/A |

## Validation status

| Item | Status |
|---|---|
| Backend DEV smoke | ✅ done in CLOUD-04 (health endpoint + browser smoke) |
| Frontend DEV smoke | ✅ done in CLOUD-04 (browser smoke, CORS fix verified) |
| Backend↔Frontend CORS | ✅ fixed and verified live (CLOUD-04 follow-up 2) |
| Cloud SQL persistence smoke | ❌ not run — instance doesn't exist |
| DEV→DEMO promotion | ❌ not run — DEMO doesn't exist |
| Monitoring alert firing | ❌ not run — no alert policy exists |
| Rollback drill | ❌ not run — no second revision to roll back from/to on a config-changed dimension beyond the two follow-ups already performed live |

## Cost-active resources (as of this freeze)

| Resource | Cost-active? | Monthly run-rate |
|---|---|---|
| `pfi-backend-dev`, `pfi-frontend-dev` (Cloud Run) | Yes, but $0 while idle (scale-to-zero) | ~$0 at current traffic |
| `pfi-github-artifact-pusher` + WIF pool/provider | Yes (exists), $0 direct cost | $0 |
| Artifact Registry image storage (backend+frontend, 2 SHA sets) | Yes | low cents/month, see `CLOUD_08_COST_ENVELOPE.md` |
| `pfi-ai-module` (pre-existing, unrelated to this session's work) | Yes, unclear ownership | scale-to-zero, ~$0 idle |
| Cloud SQL | **No** — 0 instances | $0 |
| Secret Manager | **No** — 0 secrets | $0 |
| Monitoring (alerts/uptime) | **No** — 0 resources | $0 |
| DEMO environment | **No** — does not exist | $0 |

No secret value, credential, or token appears anywhere in this manifest.
