#!/usr/bin/env bash
# Admin provisions the four systems in this demo: Financials (the accounting
# team's own console-side entries chain into this App), HRMS, Business
# Platform, and Vendor Management. Each gets a live/test key pair shown
# exactly once - matching the real console flow beat for beat.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source lib/common.sh

ADMIN_JAR="$STATE_DIR/admin_cookies.txt"
[[ -f "$ADMIN_JAR" ]] || { echo "Run 00_bootstrap_admin.sh first." >&2; exit 1; }

APPS_FILE="$STATE_DIR/apps.json"
[[ -f "$APPS_FILE" ]] || echo '{}' > "$APPS_FILE"

# app_name has no unique constraint in the schema, so re-running create_app
# would happily mint duplicates - list_apps first and skip any name this
# script already provisioned (recorded in apps.json from a prior run).
existing_apps="$(api_console app '{"_func":"list_apps"}' "$ADMIN_JAR")"

for app_name in "Financials" "HRMS" "Business Platform" "Vendor Management"; do
  already_recorded="$(jq --arg n "$app_name" 'has($n)' "$APPS_FILE")"
  live_match_id="$(echo "$existing_apps" | jq -r --arg n "$app_name" '[.[] | select(.app_name == $n)][0].id // empty')"

  if [[ "$already_recorded" == "true" ]]; then
    log "App already provisioned in a prior run: $app_name - skipping"
    continue
  elif [[ -n "$live_match_id" ]]; then
    log "App '$app_name' already exists server-side (id $live_match_id) but its key isn't in $APPS_FILE - rotate its key manually if you need it here. Skipping creation to avoid a duplicate App."
    continue
  fi

  log "Creating App: $app_name"
  resp="$(api_console app "$(jq -n --arg n "$app_name" '{_func:"create_app", app_name:$n}')" "$ADMIN_JAR")"
  echo "$resp" | jq --arg name "$app_name" '{app: $name, id, live_api_key, warning}'
  tmp="$(jq --argjson r "$resp" --arg key "$app_name" \
    '.[$key] = {id: $r.id, live_api_key: $r.live_api_key, test_api_key: $r.test_api_key}' "$APPS_FILE")"
  echo "$tmp" > "$APPS_FILE"
done

log "Apps + keys saved to $APPS_FILE (git-ignored - these are live credentials, shown only once by the real API too)"
