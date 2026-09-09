#!/usr/bin/env bash
# CLOUD-07 — revision verification, read-only.
#
# Confirms a candidate rollback target is real and sane before
# cloud-07-rollback.yml even considers proposing a traffic shift to it:
# the revision exists, belongs to the named service, is not tagged
# 'latest', and is not the same revision already serving 100% traffic
# (rolling back "to itself" is never a valid operation).
#
# Usage:
#   verify-cloudrun-revision.sh <service> <region> <candidate_revision> <stable_revision>
#
# Exits non-zero on any failed check. Never modifies traffic — read-only
# gcloud calls only.
set -euo pipefail

SERVICE="${1:?usage: verify-cloudrun-revision.sh <service> <region> <candidate_revision> <stable_revision>}"
REGION="${2:?usage: verify-cloudrun-revision.sh <service> <region> <candidate_revision> <stable_revision>}"
CANDIDATE_REVISION="${3:?usage: verify-cloudrun-revision.sh <service> <region> <candidate_revision> <stable_revision>}"
STABLE_REVISION="${4:?usage: verify-cloudrun-revision.sh <service> <region> <candidate_revision> <stable_revision>}"

if [[ "${CANDIDATE_REVISION}" == "latest" || "${STABLE_REVISION}" == "latest" ]]; then
  echo "FAILED: 'latest' is never a valid revision name (Cloud Run revisions are always a concrete, immutable name like <service>-00002-jwd)." >&2
  exit 1
fi

if [[ "${CANDIDATE_REVISION}" == "${STABLE_REVISION}" ]]; then
  echo "FAILED: candidate and stable revision are the same ('${CANDIDATE_REVISION}') — nothing to roll back." >&2
  exit 1
fi

for REVISION in "${STABLE_REVISION}" "${CANDIDATE_REVISION}"; do
  echo "Checking revision ${REVISION} belongs to service ${SERVICE} ..."
  OWNER_SERVICE="$(gcloud run revisions describe "${REVISION}" \
    --region="${REGION}" \
    --format='value(metadata.labels."serving.knative.dev/service")' 2>/dev/null || true)"

  if [[ -z "${OWNER_SERVICE}" ]]; then
    echo "FAILED: revision '${REVISION}' does not exist in region ${REGION}." >&2
    exit 1
  fi
  if [[ "${OWNER_SERVICE}" != "${SERVICE}" ]]; then
    echo "FAILED: revision '${REVISION}' belongs to service '${OWNER_SERVICE}', not '${SERVICE}'." >&2
    exit 1
  fi
done

echo "OK: both revisions exist, belong to ${SERVICE}, and are distinct."
