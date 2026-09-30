# CI/CD Design

## What already exists (inventoried this session, not created by this audit)

All three repos already have `.github/workflows/*-ci.yml`:

| Repo | Existing CI does | Gaps found |
|---|---|---|
| Backend | Build + test on JDK **17** | Dockerfile builds/runs on JDK **21** — CI does not validate the actual runtime image; version mismatch means CI green doesn't guarantee the Docker image builds or behaves the same |
| Frontend | `npm ci`, test, typecheck, lint, build, **and already builds+pushes a Docker image to GHCR** (`ghcr.io/enzoaa004/pfi-frontend`, tagged by git SHA and `latest`) on push to `main` | Pushes to GHCR, not Google Artifact Registry — real precedent for SHA-based tagging, just pointed at the wrong registry for this roadmap |
| AI module | Test on Python **3.11** | Locally the service has been run/tested on Python **3.12** this session — same version-mismatch risk as Backend |

None of the three currently run a Docker build validation step for Backend or AI, and none push to
Google Artifact Registry today.

## Minimal CI additions (design only, not implemented this stage)

For each repo, add (or fix) exactly these steps, no more:

1. **Fix version pins** — Backend CI to JDK 21, AI CI to Python 3.12, matching each Dockerfile.
2. **Docker build validation** on every PR: `docker build .` for Backend and AI (Frontend already
   does this). Fail the PR if the image doesn't build. **No push to any registry from a PR** — build
   validation only.
3. **Contract tests** — keep existing per-repo test suites as the gate; no new contract-testing
   framework introduced. (AI module's known pre-existing failures, documented in EXT-04B5/EXT-03
   material, are out of scope for this audit — not to be "fixed" incidentally by a CI change.)
4. **On push to a release branch only** (not every PR): build the image, tag `:<git-sha>` (and
   optionally a human release tag), push to Artifact Registry `pfi-containers` via Workload Identity
   Federation (see `CLOUD_SECURITY_MODEL.md`) — this step is CLOUD-02/03 work, not CLOUD-00.

## Terraform structure (design only — CLOUD-00N)

```
infra/terraform/
  modules/
    artifact-registry/
    cloud-run-service/
    iam/
    storage/
    secrets/
    cloud-sql/
    monitoring/
  environments/
    dev/
      main.tf        # wires modules together for DEV
      variables.tf
      outputs.tf
      backend.tf      # remote state config
    demo/
      main.tf        # same modules, DEMO-sized inputs
      variables.tf
      outputs.tf
      backend.tf
```

- Each module takes typed variables (service name, image, CPU/memory, min/max scale, env vars,
  secrets to mount) and exposes outputs (service URL, service account email).
- Remote state: a dedicated Cloud Storage bucket (or reuse `pfi-rm-lumbar-artifacts-2026-ef` with a
  distinct prefix — needs a decision, mildly prefer a dedicated `*-tfstate` bucket to avoid mixing
  application data and infra state) with state locking.
- `environments/dev` and `environments/demo` both instantiate the same modules with different
  variable values (min-instances, machine tier) — never separate copy-pasted `.tf` files per env.
- **This stage does not create the skeleton files.** Doing so is explicitly CLOUD-01
  ("Terraform skeleton + `validate`/`plan` only, no `apply`"), kept separate so this audit stays
  pure documentation with zero risk of an accidental `terraform apply` being run from a freshly
  written directory.

## Release flow (CLOUD-00O)

- **LOCAL → DEV → DEMO.** No PROD environment in this roadmap.
- **On PR**: unit + integration tests, lint, typecheck, Docker build validation. No deploy.
- **On push to a release branch**: build images, tag by git SHA (e.g. `backend:b097078`,
  `frontend:83b9209`, matching the existing GHCR precedent's tagging scheme), push to Artifact
  Registry, deploy to Cloud Run DEV, run smoke/health tests against DEV.
- **Promotion to DEMO**: manual approval gate (a GitHub Actions `environment` protection rule with
  a required reviewer), then deploy **the exact same image digest** already running in DEV to
  Cloud Run DEMO — no rebuild, no re-tag. This guarantees DEV and DEMO run byte-identical code.
- Never depend on a mutable `:latest` tag for any deploy step — every deploy references an explicit
  SHA tag or image digest.

## Not implemented by this document

No workflow file was created or modified. No Terraform file was created. This is a design
reference for CLOUD-01/02 to implement under their own authorization gates.
