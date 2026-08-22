#!/usr/bin/env bash
# Business Platform invoices a customer, then records the payment - the same
# App key, two ordinary hash-chained entries.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source lib/common.sh

APPS_FILE="$STATE_DIR/apps.json"
[[ -f "$APPS_FILE" ]] || { echo "Run 01_provision_apps.sh first." >&2; exit 1; }
BIZ_KEY="$(jq -r '.["Business Platform"].live_api_key' "$APPS_FILE")"

log "Business Platform: invoicing Acme Corp for 40x widgets"
resp="$(post_entry_app "$BIZ_KEY" "INV-1042" "Invoice to Acme Corp - 40x widgets" \
  '[{"account_code":"1020","type":"DEBIT","amount":12450.00},
    {"account_code":"4010","type":"CREDIT","amount":12450.00}]')"
echo "$resp" | jq '{id, sequence_number, reference_id, block_hash}'

log "Business Platform: recording payment against INV-1042"
resp="$(post_entry_app "$BIZ_KEY" "PMT-8831" "Payment received for INV-1042" \
  '[{"account_code":"1010","type":"DEBIT","amount":12450.00},
    {"account_code":"1020","type":"CREDIT","amount":12450.00}]')"
echo "$resp" | jq '{id, sequence_number, reference_id, block_hash}'
