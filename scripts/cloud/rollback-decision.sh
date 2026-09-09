#!/usr/bin/env bash
# CLOUD-07 — rollback decision engine, pure logic, no cloud calls.
#
# Takes the boolean result of each check CLOUD_07_ROLLBACK.md's Phase 2
# policy defines as automatic-rollback criteria, and decides
# ROLLBACK_REQUIRED or NO_ROLLBACK. Deliberately separate from the checks
# themselves (verify-cloudrun-revision.sh, verify-image-digest.sh,
# cloud-06-smoke.sh, and a future error-rate query) so the decision logic
# can be unit-tested with synthetic true/false inputs, independent of
# whether GCP is reachable at all.
#
# Usage:
#   rollback-decision.sh <revision_exists> <digest_match> <health_ok> <smoke_ok> <error_rate_ok>
#
# Each argument is the literal string "true" or "false". Prints exactly one
# of ROLLBACK_REQUIRED / NO_ROLLBACK to stdout, plus the reason to stderr.
# Exit code: 0 for NO_ROLLBACK, 1 for ROLLBACK_REQUIRED (so a calling
# workflow can branch on `if`, not just parse stdout).
set -euo pipefail

REVISION_EXISTS="${1:?usage: rollback-decision.sh <revision_exists> <digest_match> <health_ok> <smoke_ok> <error_rate_ok> (each true/false)}"
DIGEST_MATCH="${2:?see usage}"
HEALTH_OK="${3:?see usage}"
SMOKE_OK="${4:?see usage}"
ERROR_RATE_OK="${5:?see usage}"

for v in "$REVISION_EXISTS" "$DIGEST_MATCH" "$HEALTH_OK" "$SMOKE_OK" "$ERROR_RATE_OK"; do
  if [[ "$v" != "true" && "$v" != "false" ]]; then
    echo "FAILED: all inputs must be literal 'true' or 'false', got '$v'." >&2
    exit 2
  fi
done

if [[ "${REVISION_EXISTS}" == "false" ]]; then
  echo "ROLLBACK_REQUIRED"
  echo "reason: candidate or stable revision does not exist — cannot safely evaluate, treat as failure." >&2
  exit 1
fi

if [[ "${DIGEST_MATCH}" == "false" ]]; then
  echo "ROLLBACK_REQUIRED"
  echo "reason: digest mismatch — the running revision is not the image that was validated." >&2
  exit 1
fi

if [[ "${HEALTH_OK}" == "false" ]]; then
  echo "ROLLBACK_REQUIRED"
  echo "reason: health check failing." >&2
  exit 1
fi

if [[ "${SMOKE_OK}" == "false" ]]; then
  echo "ROLLBACK_REQUIRED"
  echo "reason: smoke test failing (frontend, auth, or CORS check)." >&2
  exit 1
fi

if [[ "${ERROR_RATE_OK}" == "false" ]]; then
  echo "ROLLBACK_REQUIRED"
  echo "reason: 5xx error rate above threshold." >&2
  exit 1
fi

echo "NO_ROLLBACK"
echo "reason: all checks passed." >&2
exit 0
