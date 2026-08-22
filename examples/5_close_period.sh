#!/usr/bin/env bash
# Revenue and expense accounts have no automatic closing mechanism - by
# design, this is a manual journal entry (see Reports.java's balance_sheet
# comments). Without it, revenue/expense activity never reaches an Equity
# account, and Assets will never equal Liabilities + Equity on the balance
# sheet. This closes the books for the period: zeroes out every revenue and
# expense account used by scripts 2-4, and folds the net result into Retained
# Earnings. Run this last, once 1-4 have all posted, using the same
# Financials App credentials as script 1.
#   API_KEY    = the Financials App's key
#   API_SECRET = the Financials App's secret
#
# The amounts below are the exact totals scripts 2-4 post (149800.00 in
# Salaries Expense, 120550.00 in Product Sales Revenue, and so on) - if you
# change those scripts' data, update this closing entry to match, or the
# balance sheet will go back to not balancing.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source lib/common.sh

: "${API_KEY:?Set API_KEY to the Financials App key from the Apps page, then re-run.}"
: "${API_SECRET:?Set API_SECRET to that App secret, then re-run.}"

log "Financials: closing the period - zeroing revenue/expense into Retained Earnings"
post_entry_app "$API_KEY" "$API_SECRET" "CLOSE-2026-08" "Close revenue and expense accounts to Retained Earnings" "2026-08-23T00:00:00Z" \
  '[{"account_code":"4010","type":"DEBIT","amount":120550.00},
    {"account_code":"3010","type":"DEBIT","amount":49670.00},
    {"account_code":"5010","type":"CREDIT","amount":10500.00},
    {"account_code":"5030","type":"CREDIT","amount":149800.00},
    {"account_code":"5040","type":"CREDIT","amount":1160.00},
    {"account_code":"5050","type":"CREDIT","amount":3620.00},
    {"account_code":"5060","type":"CREDIT","amount":990.00},
    {"account_code":"5070","type":"CREDIT","amount":4150.00}]' \
  | jq '{id, sequence_number, reference_id, block_hash}'

log "Done. Cash 60430.00, AR 46850.00 = Assets 107280.00; AP 6950.00 + Equity 100330.00 = 107280.00."
