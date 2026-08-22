#!/usr/bin/env bash
# HRMS posts its own payroll run straight to the ledger with its own API key -
# no CSV export, no night-shift batch job into the ERP.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source lib/common.sh

APPS_FILE="$STATE_DIR/apps.json"
[[ -f "$APPS_FILE" ]] || { echo "Run 01_provision_apps.sh first." >&2; exit 1; }
HRMS_KEY="$(jq -r '.["HRMS"].live_api_key' "$APPS_FILE")"

log "HRMS: posting the August 2026 payroll run"
resp="$(post_entry_app "$HRMS_KEY" "PAYROLL-2026-08" "August 2026 payroll run" \
  '[{"account_code":"5030","type":"DEBIT","amount":18500.00},
    {"account_code":"1010","type":"CREDIT","amount":18500.00}]')"
echo "$resp" | jq '{id, sequence_number, reference_id, block_hash}'
