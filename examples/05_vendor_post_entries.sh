#!/usr/bin/env bash
# Vendor Management records a supplier bill, then its payment - the mirror
# image of the Business Platform's AR lifecycle, on the AP side.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source lib/common.sh

APPS_FILE="$STATE_DIR/apps.json"
[[ -f "$APPS_FILE" ]] || { echo "Run 01_provision_apps.sh first." >&2; exit 1; }
VENDOR_KEY="$(jq -r '.["Vendor Management"].live_api_key' "$APPS_FILE")"

log "Vendor Management: recording a supplier bill"
resp="$(post_entry_app "$VENDOR_KEY" "PO-3301" "Office supplies - Staples Inc" \
  '[{"account_code":"5040","type":"DEBIT","amount":3200.00},
    {"account_code":"2010","type":"CREDIT","amount":3200.00}]')"
echo "$resp" | jq '{id, sequence_number, reference_id, block_hash}'

log "Vendor Management: paying PO-3301"
resp="$(post_entry_app "$VENDOR_KEY" "PAY-3301" "Payment for PO-3301" \
  '[{"account_code":"2010","type":"DEBIT","amount":3200.00},
    {"account_code":"1010","type":"CREDIT","amount":3200.00}]')"
echo "$resp" | jq '{id, sequence_number, reference_id, block_hash}'
