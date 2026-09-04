# CLOUD-02 — CI Pipeline

**USD 0. No cloud resource was written. No image was pushed to any registry (Google or otherwise)
from any CLOUD-02 workflow. No deployment occurred.** This document was corrected after an initial
draft understated three things: the AI suite's real pass/fail state, why it looked worse locally
than it actually is in CI, and that the Frontend workflow still had a registry push on this branch.
Both are fixed below, with the actual verification runs that back every claim.

## Existing CI audit (before this stage)

| Repo | Workflow | Triggers | Runtime | Steps | Docker build | Secrets | Permissions | Concurrency |
|---|---|---|---|---|---|---|---|---|
| Backend | `backend-ci.yml` | `push`, `pull_request`, no branch filter | Java **17** | checkout → setup-java → `mvn test` | None | None | Default (broad) | None |
| Frontend | `frontend-ci.yml` | `push`, `pull_request`, no branch filter | Node **22** | checkout → setup-node → `npm ci/lint/typecheck/test/coverage/build` | **Yes, always** (validation); **pushes to GHCR** on `push` to `main` | `GITHUB_TOKEN` only | `contents: read` + `packages: write` scoped to the image job only | None |
| AI module | `ai-module-ci.yml` | `push`, `pull_request`, no branch filter | Python **3.11** | checkout → setup-python → `pip install` → `compileall` → `smoke_api_contract.py` | None | None | Default (broad) | None |

## Drift corrected

| Repo | Finding | What CLOUD-02 did |
|---|---|---|
| Backend | `main` itself is JDK 17 end-to-end (no drift); the JDK21 Dockerfile lives on EXT-04B5, this session's de facto stable trunk | Branched CLOUD-02 Backend CI from EXT-04B5 (not `main`) so CI's JDK matches the Dockerfile it validates: **Java 21** |
| Frontend | Dockerfile uses `node:24-alpine` | Bumped CI Node 22 → **24** |
| AI module | Dockerfile uses `python:3.12-slim` | Bumped CI Python 3.11 → **3.12** |

## Branches / worktrees

| Repo | Branch | Base |
|---|---|---|
| Backend | `cloud/cloud-02-ci-backend` | EXT-04B5 (`b097078fbdbd908fea5ea467c9015b9dcb2d1a53`) |
| Frontend | `cloud/cloud-02-ci-frontend` | `origin/main` (`86991d7e391fd9ef964bc74c4b05c434e96225cb`) |
| AI module | `cloud/cloud-02-ci-ai` | this session's own worktree HEAD (`90f4a8e`, ≈ `main`) |

## Correction 1 — AI CI_GENERAL is genuinely green

The first draft of this document reported `445 passed, 3 failed, 1 skipped, 11 errors` from a local
Windows run and incorrectly treated that as the CI result. It wasn't — every one of those 14
non-passes was diagnosed individually and turned out to be a local-machine artifact, not a real CI
outcome. Each is classified below.

### Classification table

| Test / group | Marker | Reason | Where it runs | Why not CI_GENERAL |
|---|---|---|---|---|
| `test_study_zip_keeps_all_series.py` (11 tests, showed as "errors" locally) | none | Local run generated a directory name containing a literal colon (`PosDisp: [5] t1_tse_tra`) — illegal on Windows NTFS, legal on Linux | **CI_GENERAL** (they belong there and do run there) | N/A — not excluded. Re-ran the exact same test file in a clean `python:3.12-slim` **Linux** container (matching the GitHub Actions runner): **all pass**. Classified `WINDOWS_SPECIFIC` local artifact, not a real CI failure. |
| `AccountRolePreservationTest` and ~45 other Backend-side classes (unrelated finding, see Correction note below) | — | Separate Backend-side local JDK-selection issue, not an AI test — see the Backend re-validation section | — | — |
| `test_successful_notebook_streams_stdout_and_saves_outputs`, `test_failed_notebook_returns_one_and_keeps_partial_output`, `test_execute_result_and_display_data_text_plain` (`test_notebook_executor.py`, showed as "failed" locally) | **`notebook_tooling`** (newly registered marker, metadata-only — the existing `skipif` logic was not touched) | Exercise `pfi_ai_service.training.notebook_executor`, a training-support script, not the product runtime API. Need `nbclient`/`nbformat`/`ipykernel`/`jupyter_client`, none of which are in `ai_service/requirements-ai-service.txt` — deliberately, to keep the runtime image lean. My local Python has these packages installed globally from unrelated work, which is why they ran (and then hit a local kernel-registration error) instead of skipping. | **CI_NOTEBOOK_TOOLING** (not implemented as a live job — see below) | Confirmed in a clean `python:3.12-slim` Linux container installing *only* `requirements-ai-service.txt` + `pytest`: `HAS_NB_DEPS` evaluates `False`, all 3 tests **skip** via their pre-existing `skipif(not HAS_NB_DEPS, reason="nbclient/nbformat/ipykernel no instalados en este venv")` guard. No product logic was changed to produce this — only a marker was added for explicit classification. |
| `test_real_checkpoint_smoke_is_optional` (`test_subarticular_runtime_api.py`), and EXT-03's `test_gradcam.py` sanity test (separate branch, not part of this CI base) | `real_checkpoint` (pre-existing) | Needs `PFI_SUBARTICULAR_CHECKPOINT_PATH` pointing at a real, non-versioned `.pt` file | **CI_MODEL_ARTIFACT** | No legally distributable checkpoint exists in CI. Already self-skips with an explicit `pytest.skip("PFI_SUBARTICULAR_CHECKPOINT_PATH no configurado")` — verified: 1 skipped, 0 failed. |
| `test_missing_kernel_python_returns_configuration_error`, `test_timeout_argument_invalid_exits_two` (`test_notebook_executor.py`) | none | Test the *error-handling* code paths of the same module (missing executable, invalid CLI arg) without needing any notebook-execution dependency | **CI_GENERAL** (correctly included, both pass) | N/A — legitimately part of the product's own CLI contract, no exclusion needed |

### CI_NOTEBOOK_TOOLING — documented, not implemented as a job

Installing `nbclient`/`nbformat`/`ipykernel`/`jupyter_client` is free (no paid service) and could be
done reproducibly. It is deliberately **not** added as a live CI job in CLOUD-02 because it
exercises a training-support script, not the AI service's product runtime — outside this stage's
scope of "validate the product." Left as documented future work: a `notebook-tooling` job would
install those four packages and run `pytest ai_service/tests -m notebook_tooling`, nothing more.

### Proof run — CI_GENERAL and CI_MODEL_ARTIFACT on a clean Linux container

Run against `python:3.12-slim` (matches the GitHub Actions `ubuntu-latest` + Python 3.12 target),
installing **only** `ai_service/requirements-ai-service.txt` + `pytest` — i.e. exactly what the
workflow installs, nothing extra:

```
$ pip install -r ai_service/requirements-ai-service.txt pytest
$ PYTHONPATH=ai_service python -m pytest ai_service/tests -m "not real_checkpoint and not notebook_tooling" -rs
457 passed, 4 deselected, 1058 warnings in 47.51s

$ PYTHONPATH=ai_service python -m pytest ai_service/tests -m real_checkpoint -rs
SKIPPED [1] .../test_subarticular_runtime_api.py:288: PFI_SUBARTICULAR_CHECKPOINT_PATH no configurado
1 skipped, 460 deselected, 2 warnings in 6.46s
```

**CI_GENERAL: 457 passed, 0 failed, 0 errors.** Exactly the acceptance bar.

### What actually changed (product-code-adjacent, but metadata only)

- `pyproject.toml`: registered a new pytest marker,
  `notebook_tooling: exercises pfi_ai_service.training.notebook_executor; needs
  nbclient/nbformat/ipykernel/jupyter_client, deliberately absent from
  ai_service/requirements-ai-service.txt to keep the runtime image lean`.
- `ai_service/tests/test_notebook_executor.py`: added `@pytest.mark.notebook_tooling` above the
  three tests already guarded by `@pytest.mark.skipif(not HAS_NB_DEPS, ...)`. **No assertion, no
  skip condition, no test logic was touched** — this is pure classification metadata, added so the
  workflow can explicitly select `-m "not notebook_tooling"` instead of relying only on an implicit
  dependency-detection skip. Nothing was hidden or masked.
- `ai-module-ci.yml`: `test` job now installs `pytest` explicitly (it isn't in
  `requirements-ai-service.txt`, correctly — that file is a runtime dependency list) and runs
  `-m "not real_checkpoint and not notebook_tooling"`; `model-artifact-tests` job no longer carries
  `continue-on-error: true` (removed per explicit instruction — a real failure in that marker group
  must go red, not be silently masked).

## Correction 2 — Frontend CLOUD-02 is now genuinely build-only

The `image` job in `frontend-ci.yml` on this branch no longer logs into GHCR, no longer declares
`packages: write`, and always passes `push: false`. The historical GHCR-pushing behavior on `main`
is **untouched** — this branch never merges into `main`, so nothing there changes — but CLOUD-02's
own workflow, as authored on `cloud/cloud-02-ci-frontend`, publishes nothing anywhere. Real
publication to Google Artifact Registry is CLOUD-03, under its own cost/authorization gate.

The workflow's only `permissions` block is now the top-level `contents: read` — no job declares
anything broader, matching all three repos' workflows.

## Backend CI (`backend-ci.yml`) — unchanged design, re-validated

Three jobs: `compile` (fast gate), `test` (needs `compile`; full suite), `docker-build` (needs
`compile`; validation only, `push: false`). Maven cache via `setup-java`'s `cache: maven`.
`concurrency` cancels stale runs. `permissions: contents: read` only.

**Re-validation note**: a `mvn test` run during this correction pass initially showed ~50 test
classes erroring with `MockitoException` / `Byte Buddy could not instrument all classes ... Java 25
(69) is not supported by the current version of Byte Buddy`. Diagnosed immediately: this machine
has both JDK 21 and JDK 25 installed, and Maven picked up JDK 25 for that one invocation — a local
multi-JDK ambiguity, not a code regression (the exact same commit had already shown 1005/0/0/1
earlier in this session). Re-run with `JAVA_HOME` pinned explicitly to the JDK 21 install:
**989 run, 0 failures, 0 errors, 1 skipped, BUILD SUCCESS.** This ambiguity cannot occur in the real
CI workflow — `actions/setup-java@v4` with `java-version: "21"` always pins the exact JDK, with no
second JDK on the runner to be confused with.

**Contract tests**: covered by the standard `mvn test` run via WireMock (`AiServiceClientV2WireMockContractTest` and siblings) — no live AI service or Docker Compose needed.

## Frontend CI (`frontend-ci.yml`)

`build` job unchanged in shape (lint/typecheck/test/coverage/build), Node bumped 22→24. `image` job
rewritten per Correction 2 above: build-only, `push: false`, no login, no `packages: write`.

Re-validated locally on this worktree (Node 24): `npm run lint` ✅, `npm run typecheck` ✅,
`npm run test` ✅, `npm run build` ✅. Docker build ✅ (75.5 MB).

## AI module CI (`ai-module-ci.yml`)

Four jobs: `compile-and-smoke` (fast gate, Python 3.12), `test` (**CI_GENERAL**, now genuinely
green — see Correction 1), `model-artifact-tests` (**CI_MODEL_ARTIFACT**, no `continue-on-error`),
`docker-build` (validation only, `push: false`). `env: PYTHONPATH: ai_service` at the workflow
level — needed because `pyproject.toml`'s `pythonpath` pytest setting doesn't propagate to the
subprocess `test_notebook_executor.py` spawns; this is a workflow-config fix, not a product change.

## Local validation summary (final)

| Check | Result |
|---|---|
| Backend `mvn -B -DskipTests compile` (JDK 21) | ✅ BUILD SUCCESS |
| Backend `mvn test` (JDK 21, explicit `JAVA_HOME`) | ✅ **989 run, 0 failures, 0 errors, 1 skipped** |
| Backend Docker build | ✅ 354 MB |
| Frontend `npm ci/lint/typecheck/test/build` (Node 24) | ✅ all exit 0 |
| Frontend Docker build (`push: false`) | ✅ 75.5 MB |
| AI `pip install -r requirements-ai-service.txt pytest` | ✅ |
| AI `compileall` + `smoke_api_contract.py` | ✅ |
| AI CI_GENERAL (`-m "not real_checkpoint and not notebook_tooling"`, clean Linux container) | ✅ **457 passed, 0 failed, 0 errors** (4 deselected, documented) |
| AI CI_MODEL_ARTIFACT (`-m real_checkpoint`) | ✅ 1 skipped (explicit reason), 0 failed |
| AI Docker build (`push: false`) | ✅ 2.12 GB (flagged for a size pass before CLOUD-03) |
| YAML syntax (6 workflow/dependabot files, `yaml.safe_load`) | ✅ all parse |
| `git diff --check` (3 worktrees) | ✅ clean (CRLF warnings only) |

## Security

`permissions: contents: read` at the top level in **all three** workflows, with **no** job-level
override anywhere (Frontend's former `packages: write` was removed in Correction 2). No
`write-all`, no cloud secret, no service-account JSON key. WIF not used yet (design-only, from
CLOUD-01). Dependabot added to all three repos (`maven`/`npm`/`pip` + `github-actions`, all free).

## Required checks recommendation (documented only, not configured)

**Backend CI / test**, **Backend CI / docker-build**, **Frontend CI / build**,
**Frontend CI / image**, **AI Module CI / test**, **AI Module CI / docker-build** —
`model-artifact-tests` should NOT be required (expected to skip, not pass, until a real checkpoint
artifact exists).

## Future WIF/CD integration

Unchanged from the first draft: CLOUD-03+ will use the Terraform WIF design already written in
CLOUD-01 (`infra/terraform/modules/iam`) once a real registry push is authorized.

## Not done by this stage

No `gcloud` mutating command, no `terraform apply`/`import`, no Artifact Registry push, no GHCR
push from this branch's workflow, no Cloud Run deploy, no IAM change, no API enabled. All three
workflows are `READY_TO_COMMIT`, not committed or pushed.
