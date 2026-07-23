# TSI Ledger Service — Build Plan

## Context

`docs/` holds the FSD and TDD for the TSI Ledger Service: a headless, multi-tenant,
double-entry accounting API with SHA-256 hash-chained journal entries (BSA
tamper-evidence). `src/` and `web/` already contain files **copied from the
sibling reference project `/home/tsi/tsi-dpdp-cms`** ("TSI DPDP CMS") to serve as
the architectural pattern to follow: `Action`/`InterceptingFilter`/`_processor.tsi`
func-dispatch routing, `InputProcessor`/`OutputProcessor`, `PoolDB` (HikariCP),
`JSONSchemaValidator` (per-`_func` JSON-schema files), etc.

Two things are true of what's copied in today:
- **It doesn't compile as-is.** Every copied file under `src/org/tsicoop/ledger/...`
  still declares `package org.tsicoop.dpdpcms...` — a leftover from the source
  project. This has to be corrected before anything else.
- **It's the wrong shape for this project.** It's the full DPDP CMS framework
  (consent, fiduciaries, breach, grievances, wallet sync, JWT operator roles,
  branding, DNS verification, webhook dispatch, email...) — most of it has no
  analog in a ledger service. We reuse the *pattern*, not the whole class list.

**Decisions locked in already:**
1. **API style:** TSI func-dispatch, not literal REST verbs/paths from the spec.
   Every tenant/admin call is `POST` to a resource path with `_func` in the JSON
   body (e.g. `POST /api/v1/ledger {"_func":"post_entry", ...}`). The spec's
   `GET /v1/audit/verify`, `GET /v1/reports/trial-balance` etc. become `_func`
   values, not separate HTTP verbs/paths — this matches how `InterceptingFilter`
   is built (it 405s anything non-POST) and how `JSONSchemaValidator` resolves
   `/WEB-INF/validator/<_func>.jschema`.
2. **Static HTML step = a real Ops Console**, modeled on `tsi-dpdp-cms`'s
   `web/console/admin` + `web/console/dpo` pages: login, app/API-key
   provisioning, chart-of-accounts browser, journal entry browser + post-entry
   form, audit-verify runner, and report viewers.

Two gaps in the spec docs that need a default (stated here so they're visible,
not buried in code):
- **Admin/provisioning auth isn't specified anywhere** — the FSD only covers the
  tenant-facing API (`X-API-Key`). Apps/API keys have to be created by *someone*.
  Plan: a single `ADMIN_MASTER_KEY` env secret gates App/API-key provisioning and
  Ops Console login (no operator/role system — that's DPDP-CMS-specific
  complexity the ledger doesn't need).
- **No idempotency-store schema exists** in the TDD's `init.sql` even though the
  FSD mandates `Idempotency-Key`. Plan: persist it as a column + unique
  constraint on `journal_entries`, not an in-memory cache (in-memory doesn't
  survive restarts/multi-instance and the rest of this system is built around
  DB-durable state).

## Reference material

- Sibling reference project: `/home/tsi/tsi-dpdp-cms` — has the pieces not yet
  copied into this repo (`pom.xml`, `Dockerfile`, `docker-compose.yml`,
  `db/*.sql` migration-file convention, `web/console/*` page patterns). Use it
  as the pattern source, don't copy its business logic.
- Framework classes worth keeping (trim package name, adapt, drop the rest):
  `Action.java`, `DB.java`, `PoolDB.java`, `InputProcessor.java`,
  `OutputProcessor.java`, `InterceptingFilter.java`, `SystemConfig.java`,
  `JSONSchemaValidator.java`, `JacksonUtil.java`, `JSONUtil.java`,
  `StringUtil.java`, `DateUtil.java`, `RandomString.java`, `IOUtil.java`,
  `JWTUtil.java` (for Ops Console session cookies only).
- Framework classes to drop (DPDP-specific, no ledger analog):
  `BrandConfig`, `BrandingFilter`, `DnsVerifier`, `WebhookDispatcher`,
  `EventLog`, `KmsService`, `Recommendation`/`RecoUtil`, `LoginRateLimiter`,
  `TokenBlocklist`, `AsyncEmailTask`/`SendEmailTask`, `LookupHasher`,
  `DbEncryption` (no PII field-level encryption needed here), `BatchDB`,
  `HttpClient`, `CsvReader`/`CsvWriter` (add back later only if reports need
  CSV export), `PasswordHasher` (kept only if we hash the admin master key at
  rest instead of comparing env-var plaintext — decide in Step 1).
- `src/org/tsicoop/ledger/service/Audit.java` is DPDP's *audit-log* service
  (hash-chains free-text admin actions into an `audit_logs` table) — unrelated
  to the ledger's `GET /v1/audit/verify` (which walks `journal_entries.block_hash`).
  It gets replaced, not adapted.

## Step-by-step build order

Each step is a checkpoint — implement, you review, then move on. Steps 3+ are
independent "one module at a time" as requested.

### Step 1 — Project scaffolding & framework adaptation
- `pom.xml` modeled on `tsi-dpdp-cms/pom.xml`, `groupId org.tsicoop.ledger`,
  Java 17 (per TDD), trimmed dependency list (drop openpdf, jjwt only if Ops
  Console sessions need it, etc.).
- Fix every copied file's package declaration `org.tsicoop.dpdpcms.*` →
  `org.tsicoop.ledger.*`, matching actual directory layout.
- Delete the framework classes marked "drop" above; keep the "keep" list.
- Rewrite `InterceptingFilter` for the ledger's simpler auth model: no
  admin/client/bootstrap/public path categories — just `admin` (X-Admin-Key vs
  `ADMIN_MASTER_KEY`) and tenant (`X-API-Key` hash lookup against `apps`).
- `Dockerfile`, `docker-compose.yml`, `.env.example` modeled on TDD Section 2/6
  and `tsi-dpdp-cms`'s versions.
- Update `web/WEB-INF/web.xml` (drop the DPDP `JobManager` listener and
  `BrandingFilter` unless Step 1 decides otherwise) and `web/WEB-INF/_processor.tsi`
  with the route skeleton (`/api/v1/app`, `/api/v1/accounts`, `/api/v1/ledger`,
  `/api/v1/audit`, `/api/v1/reports` → placeholder classes).

### Step 2 — Database schema (`db/01_init.sql`)
Reconciles TDD's `init.sql` with the FSD gaps identified above:
- `apps`: `id`, `app_name`, `api_key_live_hash CHAR(64) UNIQUE`,
  `api_key_test_hash CHAR(64) UNIQUE` (FSD's two-tier live/test keys — TDD's
  single-column version doesn't cover this), `is_active`, `created_at`.
- `accounts`: as TDD, `UNIQUE(app_id, account_code)`, `+ is_active`.
- `journal_entries`: as TDD, `+ idempotency_key VARCHAR(255) NOT NULL`,
  `UNIQUE(app_id, idempotency_key)`, `UNIQUE(app_id, sequence_number)`.
- `entry_lines`: as TDD, `amount NUMERIC(18,4) CHECK (amount > 0)`.
- Indexes: `(app_id, account_code)`, `(app_id, sequence_number DESC)`,
  `(entry_id)`, `(account_id)`.
- Note the concurrency approach in a comment: sequence/hash-chain continuation
  requires locking the parent `apps` row (`SELECT ... FOR UPDATE`) inside the
  same transaction as the `journal_entries`/`entry_lines` insert, so two
  concurrent posts for the same app can't compute the same `sequence_number` or
  branch the hash chain. Different apps post concurrently without contention.

### Step 3 — Module: Tenant Admin (`App` provisioning)
`Accounts`-adjacent admin-only `_func`s: `create_app`, `list_apps`, `get_app`,
`rotate_api_key`, `revoke_api_key`, `deactivate_app`. Raw keys are shown once on
creation/rotation, only the SHA-256 hash is stored (mirrors FSD Section 3).
JSON-schemas under `web/WEB-INF/validator/`.

### Step 4 — Module: Chart of Accounts
Tenant-facing (`X-API-Key`) `_func`s: `create_account`, `list_accounts`,
`get_account`. Enforces `account_type` enum and natural-balance metadata from
FSD Section 2's table.

### Step 5 — Module: Core Ledger Engine (`post_entry`)
The centerpiece. Implements FSD Section 4/5 exactly:
1. Resolve tenant from `X-API-Key`.
2. Validate all `account_code`s exist/active for this app.
3. Validate ∑debits = ∑credits to the cent.
4. Idempotency check against `journal_entries(app_id, idempotency_key)`.
5. Lock the app row, fetch next `sequence_number` + last `block_hash`
   (`"0"*64` genesis for `n=1`).
6. Compute `P_n = SHA256(CanonicalJSON(metadata + lines))`,
   `H_n = SHA256(P_n + H_{n-1})`.
7. Insert `journal_entries` + `entry_lines` in one transaction, commit.
All under `PoolDB`'s connection with `autocommit=false`.

### Step 6 — Module: Audit Verification (`verify_audit`)
New class (not the copied `Audit.java`). Walks `journal_entries` for the app in
`sequence_number` order, recomputing `H_n` from stored data and comparing to
the stored `block_hash`; on first mismatch, returns the breaking
`sequence_number` per FSD Section 5/TDD Section 5.

### Step 7 — Module: Financial Reporting
`trial_balance` (as_of_date param, debit/credit totals per account) and
`balance_sheet` (`Assets = Liabilities + Equity`, per FSD Section 6 formulas).

### Step 8 — Ops Console (static HTML + JS, wired to the API)
Pages under `web/console/`, styled/structured like `tsi-dpdp-cms/web/console`:
- `login.html` — admin master key → session (JWT cookie via `JWTUtil`).
- `dashboard.html`, `apps.html` (provision/rotate keys), `accounts.html`
  (browse per app), `entries.html` (browse + a manual post-entry form for
  testing), `audit.html` (run verify, show pass/fail + breach index),
  `reports.html` (trial balance / balance sheet viewers).

### Step 9 — End-to-end verification
- `docker compose up -d`, confirm Postgres + Jetty healthy.
- Curl/Postman flow: create app → create accounts → post balanced entry →
  post unbalanced entry (expect rejection) → replay same Idempotency-Key
  (expect same result, no duplicate row) → verify audit chain (expect pass) →
  hand-edit one `entry_lines.amount` directly in Postgres → re-run verify
  (expect it to catch the tamper and report the breach index) → trial balance
  and balance sheet reflect the posted entry.
- Exercise the same flow through the Ops Console UI.

## Notes
- We'll pause after each step for your review before starting the next —
  no need to ask separately each time, that's the default cadence.

## Phase 2 — Org-wide COA, user login, reversals, raw export

Steps 1–9 above shipped v1: per-App-scoped chart of accounts, and an Ops
Console gated by a single shared `ADMIN_MASTER_KEY` (no operator/role
system). That model didn't match the real shape of the system the user is
building: **one organization-wide double-entry ledger**, not N independent
tenant ledgers. Apps (HRMS, Commerce, Lending, ...) are posting sources that
submit entries via their own API key, but they all post against **one
shared Chart of Accounts** — not their own. The Ops Console became a real
internal tool for the accounts team (Admin + Operator users, email/password
login), not a credential-pasting API debugger.

Changes (full design in the approved plan, `harmonic-nibbling-blossom`):
- **Auth model**: three tiers — Tenant (`X-API-Key`, unchanged), Console
  (JWT session from email/password login via `Auth.login`, replacing
  `ADMIN_MASTER_KEY`/`X-Admin-Key`), and Public (`/api/v1/setup`,
  `/api/v1/auth`'s `login`). `_processor.tsi` entries gained a third
  pipe-delimited field (`class|AUTH_MODE`) so `InterceptingFilter` can
  dispatch per-path instead of hardcoding one admin path.
- **Schema**: `accounts` dropped `app_id` — one global, organization-wide
  chart. New `operators` table (Admin/Operator roles, BCrypt password
  hashes). `journal_entries` gained `entry_type`/`reverses_entry_id`/
  `posted_by_user_id` for reversals; hash chain/`sequence_number` stayed
  per-`app_id` (a reversal is just the next entry in that App's chain). New
  `reversal_requests` table for the maker-checker workflow.
- **Reversal channel split**: an App reversing its own entry via
  `reverse_entry` (`X-API-Key`) posts immediately — no approval step, same
  trust level `post_entry` already has. Console-initiated reversals
  (`request_reversal`/`approve_reversal`/`reject_reversal`) require a
  *different* Admin to approve before the offsetting entry posts.
- **New modules**: `Setup` (one-time bootstrap), `Auth` (login/logout/me),
  `Users` (Admin-only operator management) — plus `web/console/setup/`,
  `web/console/users.html`, and `web/tour/index.html`.
- **Reports** moved from tenant-API-key-accessible to console-session-only,
  org-wide by default with an optional `app_id` filter; gained
  `export_entries` (raw journal-entries CSV download).
