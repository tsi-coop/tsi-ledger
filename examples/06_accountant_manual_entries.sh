#!/usr/bin/env bash
# Not everything comes from a system. The accountant (Operator) posts a
# one-off manual entry by hand into the Financials App's chain, then
# demonstrates the maker-checker correction path: nothing is ever edited or
# deleted in place, so a mistake gets reversed - by a *different* console
# user - instead of patched.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source lib/common.sh

ADMIN_JAR="$STATE_DIR/admin_cookies.txt"
APPS_FILE="$STATE_DIR/apps.json"
[[ -f "$ADMIN_JAR" && -f "$APPS_FILE" ]] || { echo "Run 00_bootstrap_admin.sh and 01_provision_apps.sh first." >&2; exit 1; }
source "$STATE_DIR/admin.env"

FINANCIALS_APP_ID="$(jq -r '.["Financials"].id' "$APPS_FILE")"

ACCOUNTANT_NAME="${ACCOUNTANT_NAME:-Priya Shah}"
ACCOUNTANT_EMAIL="${ACCOUNTANT_EMAIL:-priya@tsi-ledger.demo}"
ACCOUNTANT_PASSWORD="${ACCOUNTANT_PASSWORD:-DemoOperatorPass123!}"
ACCOUNTANT_JAR="$STATE_DIR/accountant_cookies.txt"

log "Admin: creating Operator $ACCOUNTANT_NAME <$ACCOUNTANT_EMAIL> (skips if she already exists)"
api_console users "$(jq -n --arg n "$ACCOUNTANT_NAME" --arg e "$ACCOUNTANT_EMAIL" --arg p "$ACCOUNTANT_PASSWORD" \
  '{_func:"create_operator", name:$n, email:$e, password:$p, role:"OPERATOR"}')" "$ADMIN_JAR" \
  | jq '{name, email, role}' 2>/dev/null || log "  already exists - skipping"

log "Logging in as $ACCOUNTANT_EMAIL"
api_console auth "$(jq -n --arg e "$ACCOUNTANT_EMAIL" --arg p "$ACCOUNTANT_PASSWORD" \
  '{_func:"login", email:$e, password:$p}')" "$ACCOUNTANT_JAR" | jq '{authenticated, name, role}'

log "Priya: posting the real \$45 bank maintenance fee (MANUAL-0091)"
post_entry_console "$ACCOUNTANT_JAR" "$FINANCIALS_APP_ID" "MANUAL-0091" "August bank maintenance fee" \
  '[{"account_code":"5020","type":"DEBIT","amount":45.00},
    {"account_code":"1010","type":"CREDIT","amount":45.00}]' | jq '{id, sequence_number, reference_id}'

log "Priya: fat-fingers the next one - \$450 instead of \$45 (MANUAL-0092)"
mistake_resp="$(post_entry_console "$ACCOUNTANT_JAR" "$FINANCIALS_APP_ID" "MANUAL-0092" "August bank maintenance fee" \
  '[{"account_code":"5020","type":"DEBIT","amount":450.00},
    {"account_code":"1010","type":"CREDIT","amount":450.00}]')"
echo "$mistake_resp" | jq '{id, sequence_number, reference_id}'
MISTAKE_ENTRY_ID="$(echo "$mistake_resp" | jq -r '.id')"

log "Priya: requesting a reversal of MANUAL-0092 - this only creates a request, nothing is posted yet"
req_resp="$(api_console ledger "$(jq -n --arg id "$MISTAKE_ENTRY_ID" \
  '{_func:"request_reversal", entry_id:$id, reason:"posted 450.00, should be 45.00 - typo"}')" "$ACCOUNTANT_JAR")"
echo "$req_resp" | jq '{id, status}'
REVERSAL_REQUEST_ID="$(echo "$req_resp" | jq -r '.id')"

log "Admin: approving the reversal (a different user than the requester - maker-checker)"
approve_resp="$(api_console ledger "$(jq -n --arg id "$REVERSAL_REQUEST_ID" \
  '{_func:"approve_reversal", reversal_request_id:$id}')" "$ADMIN_JAR")"
echo "$approve_resp" | jq '{id, entry_type, reverses_entry_id, block_hash}'

log "Priya: posting the corrected \$45 entry (MANUAL-0093)"
post_entry_console "$ACCOUNTANT_JAR" "$FINANCIALS_APP_ID" "MANUAL-0093" "August bank maintenance fee (corrected)" \
  '[{"account_code":"5020","type":"DEBIT","amount":45.00},
    {"account_code":"1010","type":"CREDIT","amount":45.00}]' | jq '{id, sequence_number, reference_id}'

log "Financials App entries so far - wrong, reversed, corrected:"
api_console ledger "$(jq -n --arg app "$FINANCIALS_APP_ID" '{_func:"list_entries", app_id:$app}')" "$ADMIN_JAR" \
  | jq '.entries[] | {sequence_number, reference_id, entry_type, reverses_entry_id}'
