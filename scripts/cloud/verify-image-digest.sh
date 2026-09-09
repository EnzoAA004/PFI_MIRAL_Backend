#!/usr/bin/env bash
# CLOUD-06 — image immutability check.
#
# Confirms that a given image reference's REAL digest (read from Artifact
# Registry, not trusted from a caller-supplied string) matches an expected
# digest. Used by both cloud-06-validate-dev.yml (to confirm DEV runs the
# tag it claims to) and cloud-06-promote-demo.yml (to confirm DEMO would
# receive the exact same digest already validated in DEV — never a rebuild,
# never a mismatched or :latest-resolved image).
#
# Usage:
#   verify-image-digest.sh <image-repo-path> <tag> <expected-digest>
#
# Exits non-zero (failing the calling workflow step) on any mismatch,
# missing tag, or missing digest — never silently continues.
set -euo pipefail

IMAGE_REPO="${1:?usage: verify-image-digest.sh <image-repo-path> <tag> <expected-digest>}"
TAG="${2:?usage: verify-image-digest.sh <image-repo-path> <tag> <expected-digest>}"
EXPECTED_DIGEST="${3:?usage: verify-image-digest.sh <image-repo-path> <tag> <expected-digest>}"

if [[ "${TAG}" == "latest" ]]; then
  echo "REFUSED: tag 'latest' is never a valid input to this check (CLOUD-00O: every image must be pinned by git SHA)." >&2
  exit 1
fi

echo "Resolving real digest for ${IMAGE_REPO}:${TAG} ..."
ACTUAL_DIGEST="$(gcloud artifacts docker images describe \
  "${IMAGE_REPO}:${TAG}" \
  --format='value(image_summary.digest)')"

if [[ -z "${ACTUAL_DIGEST}" ]]; then
  echo "FAILED: could not resolve a digest for ${IMAGE_REPO}:${TAG} — tag may not exist in Artifact Registry." >&2
  exit 1
fi

if [[ "${ACTUAL_DIGEST}" != "${EXPECTED_DIGEST}" ]]; then
  echo "FAILED: digest mismatch for ${IMAGE_REPO}:${TAG}" >&2
  echo "  expected: ${EXPECTED_DIGEST}" >&2
  echo "  actual:   ${ACTUAL_DIGEST}" >&2
  exit 1
fi

echo "OK: ${IMAGE_REPO}:${TAG} digest matches expected (${ACTUAL_DIGEST})."
