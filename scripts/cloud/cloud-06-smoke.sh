#!/usr/bin/env bash
# CLOUD-06 — shared smoke test, read-only against a running environment
# (DEV or DEMO). Used by cloud-06-validate-dev.yml (post-CI, pre-promotion)
# and cloud-06-promote-demo.yml (post-deploy, pre-traffic-confirmation —
# design-only in CLOUD-06, never executed against a real DEMO apply yet).
#
# Never writes anything the environment doesn't already expose for exactly
# this purpose (PFI_AUTH_DEMO_ENABLED's demo-doctor login), never touches
# Cloud SQL directly, never prints a secret.
set -euo pipefail

BACKEND_URL="${1:?usage: cloud-06-smoke.sh <backend-url> <frontend-url>}"
FRONTEND_URL="${2:?usage: cloud-06-smoke.sh <backend-url> <frontend-url>}"

echo "1/4 Backend public liveness: GET ${BACKEND_URL}/api/system/health"
curl -fsS "${BACKEND_URL}/api/system/health"
echo

echo "2/4 Backend protected endpoint (demo-doctor login, then an authenticated call)"
TOKEN="$(curl -fsS -X POST "${BACKEND_URL}/api/auth/demo-doctor" \
  -H "Content-Type: application/json" -d '{}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')"
if [[ -z "${TOKEN}" ]]; then
  echo "FAILED: demo-doctor login did not return a token." >&2
  exit 1
fi
curl -fsS "${BACKEND_URL}/api/studies" -H "Authorization: Bearer ${TOKEN}" >/dev/null
echo "OK: authenticated request accepted."

echo "3/4 Frontend root: GET ${FRONTEND_URL}/"
curl -fsS "${FRONTEND_URL}/" >/dev/null
echo "OK: frontend served."

echo "4/4 Frontend runtime config: GET ${FRONTEND_URL}/env.js"
ENV_JS="$(curl -fsS "${FRONTEND_URL}/env.js")"
if [[ "${ENV_JS}" != *"${BACKEND_URL}"* ]]; then
  echo "FAILED: env.js does not reference the expected backend URL (${BACKEND_URL})." >&2
  echo "env.js contents: ${ENV_JS}" >&2
  exit 1
fi
echo "OK: env.js wires the frontend to the expected backend."

echo "Smoke test passed."
