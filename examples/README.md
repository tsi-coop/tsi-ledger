# TSI Ledger - API demo scripts

Runnable scripts that push a realistic batch of transactions into a running
TSI Ledger instance over the same HTTP API any integration would use - no
shortcuts into the database, and no Admin password used anywhere in here.
Setup (Admin, Apps, Operators) is on you, done by hand in the console; these
scripts pick up from there and do the bulk posting.

## Prerequisites

- A running instance: `docker compose up -d` from the repo root, reachable
  at `http://localhost:8086` (override with `BASE_URL`).
- `bash`, `curl`, `jq`. `uuidgen` if you have it (falls back to `python3`).

## Workflow

1. **You** complete the console setup wizard and log in as Admin.
2. **You** create four Apps on the console's Apps page: **Financials** (check
   "Can manage Chart of Accounts" when creating it), and **HRMS**,
   **Business Platform**, **Vendor Management** (leave that box unchecked for
   these three). Copy each App's `API_KEY` and `API_SECRET` when they're
   shown - `API_SECRET` is shown once and can't be retrieved again;
   `API_KEY` stays visible on the Apps page any time.
3. **You** create any Operators you want on the Users page.
4. Run the five scripts below **one at a time**, setting `API_KEY`/
   `API_SECRET` to the right App's credentials immediately before each one.
   Run script 5 last, once 1-4 have all posted - it closes the period so the
   balance sheet actually balances.
5. **You** do the rest yourself in the console: post a manual entry, request
   and approve a reversal, pull trial balance/balance sheet/income statement,
   run audit verification. None of that is scripted here on purpose.

Nothing here writes any credential to disk - each script only ever holds
what's in its own environment for the duration of that one run.

## Why script 1 doesn't need an Admin login

Chart of Accounts writes (`create_account`/`deactivate_account`/
`activate_account`) normally require a console Admin session - the COA is
shared, structural data, not something every App gets to edit by default.
But an Admin can grant a specific App that permission (`can_manage_accounts`,
set at creation or toggled later on the Apps page), and that App can then
manage the COA with its own `API_KEY`/`API_SECRET`, exactly like any other
API call. Script 1 uses this: it authenticates as the Financials App, not as
the Admin.

## Scripts

| # | Script | Needs (env vars) | Posts |
|---|--------|-------------------|-------|
| 1 | `1_financials_create_coa.sh` | `API_KEY`/`API_SECRET` of the Financials App (`can_manage_accounts=true`) | 14-account Chart of Accounts (including Owner's Capital and Retained Earnings) + a Rs 150,000 founding capital entry |
| 2 | `2_hrms_post_payroll.sh` | `API_KEY`/`API_SECRET` of the HRMS App | 9 entries - 8 monthly payroll runs + one bonus payout, Jan–Aug 2026 |
| 3 | `3_business_post_entries.sh` | `API_KEY`/`API_SECRET` of the Business Platform App | 20 entries - 12 invoices across 11 customers + 8 payments (4 invoices left open on AR) |
| 4 | `4_vendor_post_entries.sh` | `API_KEY`/`API_SECRET` of the Vendor Management App | 20 entries - 12 bills across 5 spend categories + 8 payments (4 bills left open on AP) |
| 5 | `5_close_period.sh` | `API_KEY`/`API_SECRET` of the Financials App | 1 closing entry - zeroes revenue/expense into Retained Earnings |

51 journal entries in total, dated across January–August 2026, enough to make
the reports and audit log worth actually looking at.

### Why the balance sheet needs script 5

`balance_sheet` only totals Asset/Liability/Equity accounts - by design,
revenue and expense never fold into Equity on their own (see the comments in
`Reports.java`). Until a closing entry moves net income into an Equity
account, Assets will not equal Liabilities + Equity. Script 5 is that closing
entry: after scripts 1-4, it leaves Cash at 60430.00, Accounts Receivable at
46850.00 (Assets: 107280.00), Accounts Payable at 6950.00, and Equity
(Owner's Capital 150000.00 less a 49670.00 net loss folded into Retained
Earnings) at 100330.00 - 6950.00 + 100330.00 = 107280.00, matching Assets
exactly.

## Example

```bash
export API_KEY='key_...' API_SECRET='sk_...'    # the Financials App
./1_financials_create_coa.sh

export API_KEY='key_...' API_SECRET='sk_...'    # the HRMS App
./2_hrms_post_payroll.sh

export API_KEY='key_...' API_SECRET='sk_...'    # the Business Platform App
./3_business_post_entries.sh

export API_KEY='key_...' API_SECRET='sk_...'    # the Vendor Management App
./4_vendor_post_entries.sh

export API_KEY='key_...' API_SECRET='sk_...'    # the Financials App again
./5_close_period.sh
```

`BASE_URL` defaults to `http://localhost:8086`; override it if your instance
runs elsewhere.

## Re-running

Script 1 skips any account that already exists (by code), but its capital
entry and script 5's closing entry are not idempotent - running either twice
posts a second, unwanted entry (script 5 in particular would incorrectly
close the books a second time). Scripts 2-4 don't deduplicate by reference_id
either - re-running them against a database that already has this data will
fail with "reference already used"-style errors from a handful of underlying
constraints, not a clean skip. Wipe the database
(`docker compose down -v && docker compose up -d`) for a clean re-run, or
edit the reference_ids/dates/amounts if you just want more volume layered on
top of what's already there (remembering that script 5's amounts will then
need updating to match).
