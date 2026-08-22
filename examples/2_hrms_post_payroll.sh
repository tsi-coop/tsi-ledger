#!/usr/bin/env bash
# HRMS posts its own payroll history straight to the ledger with its own API
# key - no CSV export, no night-shift batch job into the ERP. Create the App
# yourself on the console's Apps page, copy its API_KEY/API_SECRET, and set them below.
#   API_KEY    = the HRMS App's key
#   API_SECRET = the HRMS App's secret
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source lib/common.sh

: "${API_KEY:?Set API_KEY to the HRMS App key from the Apps page, then re-run.}"
: "${API_SECRET:?Set API_SECRET to that App secret, then re-run.}"

# reference_id | description | amount | posted_at
PAYROLL_RUNS=(
  "PAYROLL-2026-01|January 2026 payroll run|17250.00|2026-01-31T09:00:00Z"
  "PAYROLL-2026-02|February 2026 payroll run|17250.00|2026-02-28T09:00:00Z"
  "PAYROLL-2026-03|March 2026 payroll run|17800.00|2026-03-31T09:00:00Z"
  "PAYROLL-2026-04|April 2026 payroll run|17800.00|2026-04-30T09:00:00Z"
  "PAYROLL-2026-05|May 2026 payroll run|18100.00|2026-05-31T09:00:00Z"
  "BONUS-2026-Q2|Q2 2026 performance bonus payout|6500.00|2026-06-15T09:00:00Z"
  "PAYROLL-2026-06|June 2026 payroll run|18100.00|2026-06-30T09:00:00Z"
  "PAYROLL-2026-07|July 2026 payroll run|18500.00|2026-07-31T09:00:00Z"
  "PAYROLL-2026-08|August 2026 payroll run|18500.00|2026-08-22T09:00:00Z"
)

for row in "${PAYROLL_RUNS[@]}"; do
  IFS='|' read -r ref desc amount posted_at <<< "$row"
  log "HRMS: $desc ($amount)"
  post_entry_app "$API_KEY" "$API_SECRET" "$ref" "$desc" "$posted_at" \
    "[{\"account_code\":\"5030\",\"type\":\"DEBIT\",\"amount\":$amount},
      {\"account_code\":\"1010\",\"type\":\"CREDIT\",\"amount\":$amount}]" \
    | jq '{id, sequence_number, reference_id, block_hash}'
done
