# CLOUD-03 — Cost Check

No cost was incurred producing this document (only free, read-only `gcloud`/`terraform plan` calls
were made — a `plan` never writes). Pricing figures reuse the sourced citations already gathered in
`CLOUD_COST_MODEL.md` (CLOUD-00) — Artifact Registry: $0.10/GB-month after a 0.5 GB free allowance
per project ([cloud.google.com/artifact-registry/pricing](https://cloud.google.com/artifact-registry/pricing)).

## Split required by this revision: infrastructure vs. artifact storage

### INFRASTRUCTURE (WIF + SA + IAM) — confirmed $0, from the real plan's 7 resources

| Resource (from the real `terraform plan`, `platform/identity`) | Cost |
|---|---|
| 1× Workload Identity Pool | $0 |
| 1× Workload Identity Provider | $0 |
| 1× service account (`pfi-github-artifact-pusher`) | $0 |
| 3× `roles/iam.workloadIdentityUser` bindings (one per repo) | $0 |
| 1× `roles/artifactregistry.writer` binding (repository-scoped) | $0 |

**All seven resources the real plan proposes to create cost exactly $0** — WIF, IAM bindings, and
service accounts carry no GCP charge regardless of how many exist.

### ARTIFACT STORAGE — the only cost-bearing category, and it's all usage-based

| Component | Measured size (CLOUD-02) |
|---|---|
| Backend | 354 MB |
| Frontend | 75.5 MB |
| AI | **2.12 GB** |
| **Total, one SHA-tagged set** | **≈ 2.54 GB** |

No image has been pushed yet — this is 100% projected, not incurred.

## Per-resource cost table

| Resource | Existing/New | Monthly fixed cost | Usage cost | Potential hidden cost |
|---|---|---|---|---|
| Workload Identity Pool | New (design only) | **$0** | None | None — WIF itself has no per-use or per-identity charge |
| Workload Identity Provider | New (design only) | **$0** | None | None |
| `pfi-github-artifact-pusher` service account | New (design only) | **$0** | None | None — IAM identities are free |
| IAM bindings (repo-scoped writer, workloadIdentityUser) | New (design only) | **$0** | None | None |
| `pfi-containers` Artifact Registry repo | **Existing**, reused | Already-incurred, unaffected by this stage's design work | Storage only (see below) | None new from this stage |
| Image storage — first push, all 3 components | New (once authorized + pushed) | ~**$0.20–0.25/month** at 0 GB free-tier remainder (see calc below) | Grows per additional SHA-tagged push not cleaned up | Unbounded growth without a retention policy (designed, not applied — see Security Review) |
| Vulnerability scanning (`containerscanning.googleapis.com`) | Not enabled, not proposed | $0 | N/A | Would add per-image scan cost if ever enabled — **explicitly not enabled by this stage** |
| Network egress (pulling images out of Artifact Registry, e.g. by Cloud Run in a future stage) | N/A yet | $0 | Applies once something actually pulls the image (CLOUD-04+) | Out of scope until deployment exists |

## Image storage calculation (measured sizes, from CLOUD-02)

| Component | Measured size |
|---|---|
| Backend | 354 MB |
| Frontend | 75.5 MB |
| AI | **2.12 GB** |
| **Total, one SHA-tagged set** | **≈ 2.54 GB** |

`pfi-containers` already holds 433.7 MB (the pre-existing `pfi-ai-module`). Adding one full
SHA-tagged set of all three new images: 433.7 MB + 2,540 MB ≈ **2.97 GB** total repo size. After
the 0.5 GB per-project free allowance, ≈ 2.47 GB billable × $0.10/GB-month ≈ **$0.25/month** for
the *first* push of all three.

**Without a retention policy, this grows linearly with every additional SHA pushed** — e.g. 10
SHA-tagged sets (a realistic count during active CLOUD-04/05/06 iteration) ≈ 25.4 GB of new image
data ≈ **$2.50/month** on top of the baseline, still cheap in absolute terms but not free forever.
This is exactly why a retention policy is designed (not applied) in `CLOUD_03_SECURITY_REVIEW.md`.

## The AI image specifically (2.12 GB)

Called out per the CLOUD-03 instruction's explicit ask: at current size, each additional AI-image
push costs roughly $0.21/month in incremental storage (2.12 GB × $0.10/GB) if never cleaned up.
Not prohibitively expensive at low push frequency, but the largest single contributor by far (AI is
~83% of one SHA-tagged set's total size). No image optimization is attempted in this stage
(instruction: "No optimizar todavía salvo cambio pequeño") — flagged as a good candidate for a
future small win (e.g., multi-stage trimming, `--no-cache-dir` on pip, checking whether the full
CPU PyTorch wheel pulls anything unused) before CLOUD-03's *first real push* of the AI image is
authorized, if cost sensitivity warrants it.

## Recommendation reflected in the authorization request

Because of the size gap between AI (2.12 GB) and Backend+Frontend (429.5 MB combined), the
pre-authorization review treats **AI's first push as a separate yes/no**, not bundled automatically
with Backend/Frontend approval — consistent with the instruction's "No asumir autorización para AI
por default."
