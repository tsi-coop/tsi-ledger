# TSI Ledger — API demo scripts

Runnable scripts that push real transactions into a running TSI Ledger
instance over the same HTTP API any integration would use — no shortcuts
into the database. Built to support the video walkthrough (each script maps
to one beat of the script), but they're just as useful for kicking the tires
by hand.

## Prerequisites

- A running instance: `docker compose up -d` from the repo root, reachable
  at `http://localhost:8086` (override with `BASE_URL`).
- `bash`, `curl`, `jq`. `uuidgen` if you have it (falls back to `python3`).

## What each script does

| Script | Persona | What it demonstrates |
|--------|---------|----------------------|
| `00_bootstrap_admin.sh` | Admin | First-time setup (or logs in if already configured) |
| `01_provision_apps.sh` | Admin | Provisions 4 Apps — Financials, HRMS, Business Platform, Vendor Management — each with a live/test key pair shown once |
| `02_financials_create_coa.sh` | Admin/Finance, via API | Builds the 8-account Chart of Accounts. This calls `accounts.create_account` as the console Admin, **not** with an App's `X-API-Key` — the API itself returns 403 for a tenant key here, because the Chart of Accounts is shared, structural, org-wide data, not something an individual App edits |
| `03_hrms_post_payroll.sh` | HRMS (App key) | Posts a payroll run via `X-API-Key` |
| `04_business_post_entries.sh` | Business Platform (App key) | Posts an invoice, then a payment — the AR lifecycle |
| `05_vendor_post_entries.sh` | Vendor Management (App key) | Posts a supplier bill, then its payment — the AP lifecycle |
| `06_accountant_manual_entries.sh` | Accountant (Operator) | Creates the Operator, posts a manual entry, fat-fingers one, requests a reversal, has the Admin approve it (maker-checker), posts the correction |
| `07_reports.sh` | Accountant | Pulls trial balance, balance sheet, income statement, and a raw CSV export — reflecting every entry posted above |
| `08_verify_audit.sh` | Developer | Recomputes and checks the hash chain for every App |

Run them individually in order, or all at once:

```bash
./run_all.sh
```

Each script is safe to re-run — `01`/`02`/`06` skip or no-op around state that
already exists (an App/account/operator that's already there).

## State

Scripts write to `.state/` next to this README: cookie jars, the admin's
credentials, and — critically — the **live API keys** generated for each
App. `.state/` is git-ignored. Treat its contents as secrets; delete it
(`rm -rf .state`) to start over against a fresh database.

## Overriding identities

```bash
ADMIN_EMAIL=me@example.com ADMIN_PASSWORD='at-least-12-chars' ./00_bootstrap_admin.sh
ACCOUNTANT_EMAIL=jane@example.com ./06_accountant_manual_entries.sh
BASE_URL=http://localhost:9000 ./run_all.sh
```
