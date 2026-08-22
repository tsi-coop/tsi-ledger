#!/usr/bin/env bash
# Shared helpers for the TSI Ledger demo scripts in this folder.
# Every script here talks to a *running* instance (docker compose up -d)
# over the same HTTP API a real integration would use - nothing here reaches
# into the database directly. App credentials (API_KEY/API_SECRET) are
# supplied by you via environment variables - nothing here creates or stores
# them, and no script needs an Admin console login at all.

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8086}"

command -v jq >/dev/null 2>&1 || { echo "jq is required: https://jqlang.github.io/jq/" >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { echo "curl is required." >&2; exit 1; }

log() { echo "-> $*" >&2; }

new_key() {
  if command -v uuidgen >/dev/null 2>&1; then
    uuidgen
  else
    python3 -c 'import uuid; print(uuid.uuid4())'
  fi
}

# api_app MODULE API_KEY API_SECRET JSON_BODY [IDEMPOTENCY_KEY]
# POSTs to /api/v1/{module} authenticated with an App's X-API-Key/X-API-Secret
# pair - this is the path an HRMS, a commerce platform, or an AI agent would
# actually use. Prints the response body on stdout and returns non-zero on a
# non-2xx status.
api_app() {
  local module="$1" api_key="$2" api_secret="$3" body="$4" idem="${5:-}"
  local args=(-sS -w '\n%{http_code}' -X POST "$BASE_URL/api/v1/$module"
    -H "X-API-Key: $api_key" -H "X-API-Secret: $api_secret" -H 'Content-Type: application/json')
  [[ -n "$idem" ]] && args+=(-H "Idempotency-Key: $idem")
  local resp status body_out
  resp="$(curl "${args[@]}" -d "$body")"
  status="${resp##*$'\n'}"
  body_out="${resp%$'\n'*}"
  echo "$body_out"
  [[ "$status" =~ ^2 ]] || { echo "app call to '$module' failed (HTTP $status): $body_out" >&2; return 1; }
}

# post_entry_app API_KEY API_SECRET REFERENCE_ID DESCRIPTION POSTED_AT LINES_JSON
# LINES_JSON is a JSON array literal: [{"account_code":"1010","type":"DEBIT","amount":45.00}, ...]
post_entry_app() {
  local api_key="$1" api_secret="$2" reference_id="$3" description="$4" posted_at="$5" lines_json="$6"
  local body
  body="$(jq -n --arg ref "$reference_id" --arg desc "$description" --arg pa "$posted_at" \
    --argjson lines "$lines_json" \
    '{_func:"post_entry", reference_id:$ref, description:$desc, posted_at:$pa, lines:$lines}')"
  api_app ledger "$api_key" "$api_secret" "$body" "$(new_key)"
}
