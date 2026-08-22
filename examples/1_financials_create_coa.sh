#!/usr/bin/env bash
# The Chart of Accounts is organization-wide, structural data, so
# create_account normally requires a console Admin session - unless the
# calling App has been granted can_manage_accounts=true (set that checkbox
# when you create the Financials App on the console's Apps page). With that
# flag set, this Financials App posts the whole Chart of Accounts, plus the
# founding capital entry, with its own API_KEY/API_SECRET - no Admin password
# involved at all.
#   API_KEY    = the Financials App's key
#   API_SECRET = the Financials App's secret
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source lib/common.sh

: "${API_KEY:?Set API_KEY to the Financials App key from the Apps page, then re-run.}"
: "${API_SECRET:?Set API_SECRET to that App secret, then re-run.}"

ACCOUNTS=(
  "1010|Cash|ASSET"
  "1020|Accounts Receivable|ASSET"
  "2010|Accounts Payable|LIABILITY"
  "3000|Owner's Capital|EQUITY"
  "3010|Retained Earnings|EQUITY"
  "4010|Product Sales Revenue|REVENUE"
  "4020|Service Revenue|REVENUE"
  "5010|Rent Expense|EXPENSE"
  "5020|Bank Fees Expense|EXPENSE"
  "5030|Salaries Expense|EXPENSE"
  "5040|Office Supplies Expense|EXPENSE"
  "5050|Software Subscriptions Expense|EXPENSE"
  "5060|Utilities Expense|EXPENSE"
  "5070|Marketing Expense|EXPENSE"
)

for row in "${ACCOUNTS[@]}"; do
  IFS='|' read -r code name type <<< "$row"
  log "create_account $code  $name  ($type)"
  body="$(jq -n --arg c "$code" --arg n "$name" --arg t "$type" \
    '{_func:"create_account", account_code:$c, account_name:$n, account_type:$t}')"
  if resp="$(api_app accounts "$API_KEY" "$API_SECRET" "$body" 2>/dev/null)"; then
    echo "$resp" | jq '{account_code, account_name, account_type, natural_balance}'
  else
    log "  already exists - skipping"
  fi
done

log "Chart of Accounts:"
api_app accounts "$API_KEY" "$API_SECRET" '{"_func":"list_accounts"}' \
  | jq '[.[] | {account_code, account_name, account_type}]'

# Opening capital - without this, HRMS/Vendor Management would be spending cash
# the business never had. Dated before every other entry in this dataset so it
# reads as the very first thing that happened. Run 5_close_period.sh after
# scripts 2-4 to fold net income into Retained Earnings - only then does
# balance_sheet actually balance (Assets = Liabilities + Equity); it won't
# until that closing entry exists, by design (see 5_close_period.sh).
log "Financials: recording the founding capital contribution"
post_entry_app "$API_KEY" "$API_SECRET" "CAPITAL-2026-01" "Owner's initial capital contribution" "2026-01-01T00:00:00Z" \
  '[{"account_code":"1010","type":"DEBIT","amount":150000.00},
    {"account_code":"3000","type":"CREDIT","amount":150000.00}]' \
  | jq '{id, sequence_number, reference_id, block_hash}'
