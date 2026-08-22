#!/usr/bin/env bash
# The Chart of Accounts is organization-wide, structural data - create_account
# is Admin-only, enforced server-side (an App's X-API-Key gets a 403 here,
# not just a hidden UI button). So this isn't a "Financials App" posting with
# its own key; it's the Finance/Admin team automating their own console work
# via the same API instead of clicking six "New Account" forms by hand.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source lib/common.sh

ADMIN_JAR="$STATE_DIR/admin_cookies.txt"
[[ -f "$ADMIN_JAR" ]] || { echo "Run 00_bootstrap_admin.sh first." >&2; exit 1; }

ACCOUNTS=(
  "1010|Cash|ASSET"
  "1020|Accounts Receivable|ASSET"
  "2010|Accounts Payable|LIABILITY"
  "4010|Sales Revenue|REVENUE"
  "5010|Rent Expense|EXPENSE"
  "5020|Bank Fees Expense|EXPENSE"
  "5030|Salaries Expense|EXPENSE"
  "5040|Office Supplies Expense|EXPENSE"
)

for row in "${ACCOUNTS[@]}"; do
  IFS='|' read -r code name type <<< "$row"
  log "create_account $code  $name  ($type)"
  if resp="$(api_console accounts "$(jq -n --arg c "$code" --arg n "$name" --arg t "$type" \
      '{_func:"create_account", account_code:$c, account_name:$n, account_type:$t}')" "$ADMIN_JAR" 2>/dev/null)"; then
    echo "$resp" | jq '{account_code, account_name, account_type, natural_balance}'
  else
    log "  already exists - skipping"
  fi
done

log "Chart of Accounts:"
api_console accounts '{"_func":"list_accounts"}' "$ADMIN_JAR" | jq '[.[] | {account_code, account_name, account_type}]'
