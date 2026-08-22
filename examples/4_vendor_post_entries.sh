#!/usr/bin/env bash
# Vendor Management records bills across five spend categories - rent,
# software, office supplies, utilities, marketing - and pays most of them,
# leaving a few open on Accounts Payable on purpose. Create the App yourself
# on the console's Apps page, copy its API_KEY/API_SECRET, and set them below.
#   API_KEY    = the Vendor Management App's key
#   API_SECRET = the Vendor Management App's secret
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source lib/common.sh

: "${API_KEY:?Set API_KEY to the Vendor Management App key from the Apps page, then re-run.}"
: "${API_SECRET:?Set API_SECRET to that App secret, then re-run.}"

# reference_id | vendor | account_code | amount | posted_at
BILLS=(
  "PO-2001|Staples Inc (office supplies)|5040|620.00|2026-01-15T11:00:00Z"
  "PO-2002|AWS (software)|5050|1250.00|2026-01-20T11:00:00Z"
  "PO-2003|City Power & Water (utilities)|5060|480.00|2026-02-01T11:00:00Z"
  "PO-2004|Meta Ads (marketing)|5070|2200.00|2026-02-14T11:00:00Z"
  "PO-2005|Regus Office Space (rent)|5010|3500.00|2026-03-01T11:00:00Z"
  "PO-2006|Regus Office Space (rent)|5010|3500.00|2026-04-01T11:00:00Z"
  "PO-2007|Staples Inc (office supplies)|5040|540.00|2026-05-10T11:00:00Z"
  "PO-2008|AWS (software)|5050|1380.00|2026-05-20T11:00:00Z"
  "PO-2009|City Power & Water (utilities)|5060|510.00|2026-06-01T11:00:00Z"
  "PO-2010|Google Ads (marketing)|5070|1950.00|2026-06-20T11:00:00Z"
  "PO-2011|CloudOps (software)|5050|990.00|2026-07-15T11:00:00Z"
  "PO-2012|Regus Office Space (rent)|5010|3500.00|2026-08-01T11:00:00Z"
)

# reference_id | bill_ref | vendor | amount | posted_at - deliberately skips
# PO-2009..2012, leaving four bills open on Accounts Payable.
PAYMENTS=(
  "PAY-2001|PO-2001|Staples Inc|620.00|2026-01-28T15:00:00Z"
  "PAY-2002|PO-2002|AWS|1250.00|2026-02-03T15:00:00Z"
  "PAY-2003|PO-2003|City Power & Water|480.00|2026-02-10T15:00:00Z"
  "PAY-2004|PO-2004|Meta Ads|2200.00|2026-02-25T15:00:00Z"
  "PAY-2005|PO-2005|Regus Office Space|3500.00|2026-03-10T15:00:00Z"
  "PAY-2006|PO-2006|Regus Office Space|3500.00|2026-04-10T15:00:00Z"
  "PAY-2007|PO-2007|Staples Inc|540.00|2026-05-25T15:00:00Z"
  "PAY-2008|PO-2008|AWS|1380.00|2026-06-05T15:00:00Z"
)

for row in "${BILLS[@]}"; do
  IFS='|' read -r ref vendor account amount posted_at <<< "$row"
  log "Vendor Management: bill from $vendor ($amount) - $ref"
  post_entry_app "$API_KEY" "$API_SECRET" "$ref" "Bill from $vendor" "$posted_at" \
    "[{\"account_code\":\"$account\",\"type\":\"DEBIT\",\"amount\":$amount},
      {\"account_code\":\"2010\",\"type\":\"CREDIT\",\"amount\":$amount}]" \
    | jq '{id, sequence_number, reference_id, block_hash}'
done

for row in "${PAYMENTS[@]}"; do
  IFS='|' read -r ref bill_ref vendor amount posted_at <<< "$row"
  log "Vendor Management: paying $vendor for $bill_ref ($amount)"
  post_entry_app "$API_KEY" "$API_SECRET" "$ref" "Payment to $vendor for $bill_ref" "$posted_at" \
    "[{\"account_code\":\"2010\",\"type\":\"DEBIT\",\"amount\":$amount},
      {\"account_code\":\"1010\",\"type\":\"CREDIT\",\"amount\":$amount}]" \
    | jq '{id, sequence_number, reference_id, block_hash}'
done
