#!/usr/bin/env bash
# Shared helpers for the TSI Ledger demo scripts in this folder.
# Every script here talks to a *running* instance (docker compose up -d)
# over the same HTTP API a real integration would use - nothing here reaches
# into the database directly.

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8086}"
EXAMPLES_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_DIR="$EXAMPLES_DIR/.state"
mkdir -p "$STATE_DIR"

command -v jq >/dev/null 2>&1 || { echo "jq is required: https://jqlang.github.io/jq/" >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { echo "curl is required." >&2; exit 1; }

log() { echo "-> $*" >&2; }

iso_now() { date -u +"%Y-%m-%dT%H:%M:%SZ"; }

new_key() {
  if command -v uuidgen >/dev/null 2>&1; then
    uuidgen
  else
    python3 -c 'import uuid; print(uuid.uuid4())'
  fi
}

# api_console MODULE JSON_BODY COOKIE_JAR [IDEMPOTENCY_KEY]
# POSTs to /api/v1/{module} authenticated with a console session cookie jar
# (the same auth Auth.login hands the browser console). Prints the response
# body on stdout and returns non-zero on a non-2xx status.
api_console() {
  local module="$1" body="$2" jar="$3" idem="${4:-}"
  local args=(-sS -c "$jar" -b "$jar" -w '\n%{http_code}' -X POST "$BASE_URL/api/v1/$module"
    -H 'Content-Type: application/json')
  [[ -n "$idem" ]] && args+=(-H "Idempotency-Key: $idem")
  local resp status body_out
  resp="$(curl "${args[@]}" -d "$body")"
  status="${resp##*$'\n'}"
  body_out="${resp%$'\n'*}"
  echo "$body_out"
  [[ "$status" =~ ^2 ]] || { echo "console call to '$module' failed (HTTP $status): $body_out" >&2; return 1; }
}

# api_app MODULE API_KEY JSON_BODY [IDEMPOTENCY_KEY]
# POSTs to /api/v1/{module} authenticated with an App's X-API-Key - this is
# the path an HRMS, a commerce platform, or an AI agent would actually use.
api_app() {
  local module="$1" api_key="$2" body="$3" idem="${4:-}"
  local args=(-sS -w '\n%{http_code}' -X POST "$BASE_URL/api/v1/$module"
    -H "X-API-Key: $api_key" -H 'Content-Type: application/json')
  [[ -n "$idem" ]] && args+=(-H "Idempotency-Key: $idem")
  local resp status body_out
  resp="$(curl "${args[@]}" -d "$body")"
  status="${resp##*$'\n'}"
  body_out="${resp%$'\n'*}"
  echo "$body_out"
  [[ "$status" =~ ^2 ]] || { echo "app call to '$module' failed (HTTP $status): $body_out" >&2; return 1; }
}

# post_entry_app API_KEY REFERENCE_ID DESCRIPTION LINES_JSON
# LINES_JSON is a JSON array literal: [{"account_code":"1010","type":"DEBIT","amount":45.00}, ...]
post_entry_app() {
  local api_key="$1" reference_id="$2" description="$3" lines_json="$4"
  local body
  body="$(jq -n --arg ref "$reference_id" --arg desc "$description" --arg pa "$(iso_now)" \
    --argjson lines "$lines_json" \
    '{_func:"post_entry", reference_id:$ref, description:$desc, posted_at:$pa, lines:$lines}')"
  api_app ledger "$api_key" "$body" "$(new_key)"
}

# post_entry_console COOKIE_JAR APP_ID REFERENCE_ID DESCRIPTION LINES_JSON
# Console-originated manual entry: the caller (an Admin or Operator session)
# names which App's ledger/hash-chain sequence this belongs to explicitly,
# since a console login isn't itself an App.
post_entry_console() {
  local jar="$1" app_id="$2" reference_id="$3" description="$4" lines_json="$5"
  local body
  body="$(jq -n --arg app "$app_id" --arg ref "$reference_id" --arg desc "$description" --arg pa "$(iso_now)" \
    --argjson lines "$lines_json" \
    '{_func:"post_entry", app_id:$app, reference_id:$ref, description:$desc, posted_at:$pa, lines:$lines}')"
  api_console ledger "$body" "$jar" "$(new_key)"
}
