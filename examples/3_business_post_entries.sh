#!/usr/bin/env bash
# Business Platform invoices a dozen different customers over the year, then
# records payment on most of them - a handful are left open on purpose, so
# Accounts Receivable isn't just a number that always nets to zero.
# Create the App yourself on the console's Apps page, copy its API_KEY/API_SECRET, and
# set them below.
#   API_KEY    = the Business Platform App's key
#   API_SECRET = the Business Platform App's secret
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source lib/common.sh

: "${API_KEY:?Set API_KEY to the Business Platform App key from the Apps page, then re-run.}"
: "${API_SECRET:?Set API_SECRET to that App secret, then re-run.}"

# reference_id | customer | amount | posted_at
INVOICES=(
  "INV-1001|Acme Corp|8200.00|2026-01-10T10:00:00Z"
  "INV-1002|Globex Corporation|5400.00|2026-01-22T10:00:00Z"
  "INV-1003|Initech|9750.00|2026-02-05T10:00:00Z"
  "INV-1004|Umbrella Retail|3100.00|2026-02-19T10:00:00Z"
  "INV-1005|Wayne Enterprises|15200.00|2026-03-08T10:00:00Z"
  "INV-1006|Stark Industries|21000.00|2026-03-27T10:00:00Z"
  "INV-1007|Wonka Industries|4750.00|2026-04-14T10:00:00Z"
  "INV-1008|Hooli Inc|6300.00|2026-05-02T10:00:00Z"
  "INV-1009|Soylent Corp|11800.00|2026-05-29T10:00:00Z"
  "INV-1010|Cyberdyne Systems|9200.00|2026-06-18T10:00:00Z"
  "INV-1011|Massive Dynamic|13400.00|2026-07-09T10:00:00Z"
  "INV-1012|Acme Corp|12450.00|2026-08-11T10:00:00Z"
)

# reference_id | invoice_ref | customer | amount | posted_at - deliberately
# skips INV-1009..1012, leaving four invoices open on Accounts Receivable.
PAYMENTS=(
  "PMT-1001|INV-1001|Acme Corp|8200.00|2026-01-25T14:00:00Z"
  "PMT-1002|INV-1002|Globex Corporation|5400.00|2026-02-03T14:00:00Z"
  "PMT-1003|INV-1003|Initech|9750.00|2026-02-20T14:00:00Z"
  "PMT-1004|INV-1004|Umbrella Retail|3100.00|2026-03-01T14:00:00Z"
  "PMT-1005|INV-1005|Wayne Enterprises|15200.00|2026-03-25T14:00:00Z"
  "PMT-1006|INV-1006|Stark Industries|21000.00|2026-04-10T14:00:00Z"
  "PMT-1007|INV-1007|Wonka Industries|4750.00|2026-04-30T14:00:00Z"
  "PMT-1008|INV-1008|Hooli Inc|6300.00|2026-05-20T14:00:00Z"
)

for row in "${INVOICES[@]}"; do
  IFS='|' read -r ref customer amount posted_at <<< "$row"
  log "Business Platform: invoicing $customer ($amount) - $ref"
  post_entry_app "$API_KEY" "$API_SECRET" "$ref" "Invoice to $customer" "$posted_at" \
    "[{\"account_code\":\"1020\",\"type\":\"DEBIT\",\"amount\":$amount},
      {\"account_code\":\"4010\",\"type\":\"CREDIT\",\"amount\":$amount}]" \
    | jq '{id, sequence_number, reference_id, block_hash}'
done

for row in "${PAYMENTS[@]}"; do
  IFS='|' read -r ref inv_ref customer amount posted_at <<< "$row"
  log "Business Platform: payment received from $customer for $inv_ref ($amount)"
  post_entry_app "$API_KEY" "$API_SECRET" "$ref" "Payment received from $customer for $inv_ref" "$posted_at" \
    "[{\"account_code\":\"1010\",\"type\":\"DEBIT\",\"amount\":$amount},
      {\"account_code\":\"1020\",\"type\":\"CREDIT\",\"amount\":$amount}]" \
    | jq '{id, sequence_number, reference_id, block_hash}'
done
