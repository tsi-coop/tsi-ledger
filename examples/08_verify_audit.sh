#!/usr/bin/env bash
# Recomputes each App's hash chain from what's actually stored and compares
# it to what was recorded at post time - the tamper-evidence check, run
# per-App since the chain itself is per-App.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source lib/common.sh

ADMIN_JAR="$STATE_DIR/admin_cookies.txt"
APPS_FILE="$STATE_DIR/apps.json"
[[ -f "$ADMIN_JAR" && -f "$APPS_FILE" ]] || { echo "Run 00_bootstrap_admin.sh and 01_provision_apps.sh first." >&2; exit 1; }

jq -r 'keys[]' "$APPS_FILE" | while IFS= read -r app_name; do
  app_id="$(jq -r --arg n "$app_name" '.[$n].id' "$APPS_FILE")"
  log "verify_audit: $app_name"
  api_console audit "$(jq -n --arg a "$app_id" '{_func:"verify_audit", app_id:$a}')" "$ADMIN_JAR" \
    | jq '{valid, total_entries, entries_verified, last_block_hash}'
done
