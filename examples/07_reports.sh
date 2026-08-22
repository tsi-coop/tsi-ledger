#!/usr/bin/env bash
# Reports are console-only and computed live, org-wide across every App
# against the shared Chart of Accounts - no month-end close, no batch job
# that ran last night. Run this after 02-06 so there's something to report on.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source lib/common.sh

ADMIN_JAR="$STATE_DIR/admin_cookies.txt"
[[ -f "$ADMIN_JAR" ]] || { echo "Run 00_bootstrap_admin.sh first." >&2; exit 1; }

MONTH_START="$(date -u +%Y-%m-01)"
TODAY="$(date -u +%Y-%m-%d)"

log "Trial balance (debits = credits, across every account):"
api_console reports '{"_func":"trial_balance"}' "$ADMIN_JAR" | jq .

log "Balance sheet:"
api_console reports '{"_func":"balance_sheet"}' "$ADMIN_JAR" | jq .

log "Income statement ($MONTH_START to $TODAY):"
api_console reports "$(jq -n --arg f "$MONTH_START" --arg t "$TODAY" \
  '{_func:"income_statement", date_from:$f, date_to:$t}')" "$ADMIN_JAR" | jq .

CSV_PATH="$STATE_DIR/journal_entries_export.csv"
log "Exporting raw journal entries to $CSV_PATH"
curl -sS -c "$ADMIN_JAR" -b "$ADMIN_JAR" -X POST "$BASE_URL/api/v1/reports" \
  -H 'Content-Type: application/json' -d '{"_func":"export_entries"}' -o "$CSV_PATH"
log "$(wc -l < "$CSV_PATH") lines written"
head -5 "$CSV_PATH"
