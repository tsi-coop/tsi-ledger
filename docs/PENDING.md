# Pending / Known Gaps

Tracked gaps found during a functional-completeness review (2026-08-16) of
the core ledger engine and Ops Console. Not blockers to the soft launch, but
should be closed or explicitly deferred with a reason.

## 1. No self-service password change in the console

`Users.change_own_password` exists as an API function with a validator
schema (`web/WEB-INF/validator/change_own_password.jschema`), but no console
page calls it. An Operator who is still signed in has no way to change
their own password without an Admin doing it for them.

**Fix:** add a "change my password" action to the console (e.g. from the
user menu / profile), calling the existing `change_own_password` function.
No backend work needed, UI only. (Separate from the locked-out case below,
which is now handled.)

**Done (2026-08-17):** the locked-out case - an Operator who can't sign in
at all - is handled via an Admin-issued, single-use reset key:
`Users.generate_reset_key` (Admin-only; `web/WEB-INF/validator/generate_reset_key.jschema`)
mints a key, persists only its SHA-256 hash + a 24h expiry on `operators`,
and returns the raw key once, wired up from the "Password Reset Key" section
of the Manage Operator modal in `users.html`. `Auth.reset_with_key` (PUBLIC;
`web/WEB-INF/validator/reset_with_key.jschema`) lets the Operator redeem it
themselves - email + key + new password - at the new
`web/console/reset-password.html`, linked from `login.html`. The key is
cleared on redemption (single-use) and the schema addition
(`operators.reset_key_hash` / `reset_key_expires_at`) lives in `db/01_init.sql`,
which only runs on a fresh Postgres volume - existing dev databases need the
two columns added by hand.

## 2. No automated test suite

**Done (2026-08-17):** added a `test/` source tree (`pom.xml`'s
`testSourceDirectory`, plus a `junit:junit:4.13.2` test-scope dependency and
an explicit `maven-surefire-plugin:3.2.5` - the pre-existing default-bound
2.12.4 couldn't resolve its JUnit4 provider offline). `mvn test` now runs:

- `HashChainTest` - pure unit tests (no DB): canonical-payload determinism,
  independence from submitted line order, sensitivity to every input field,
  the payload-hash/block-hash formula against an independently computed
  SHA-256, genesis hash, a 3-entry chain recomputing identically on a second
  pass (what `verify_audit` relies on), tampering with an earlier entry
  breaking every later block hash, and description/reference_id text that
  can't break out of its JSON string.
- `LedgerIntegrationTest` - `post_entry`/`reverse_entry` against a real
  Postgres (balance check rejects/accepts, idempotency replay doesn't
  double-post, 8 concurrent posts to the same App produce an unbroken
  sequential hash chain via the `FOR UPDATE` app-row lock, and reversing
  twice is blocked). Drives `Ledger.post(req, res)` directly through a
  `java.lang.reflect.Proxy`-based `HttpServletRequest`/`Response` double
  (`test/.../support/FakeHttp.java`) rather than pulling in a servlet
  container or a mocking library. Self-skips via `Assume` with a clear
  message when `POSTGRES_HOST` isn't set, so plain `mvn test` stays green
  with no DB available; each run creates and tears down its own App/Accounts
  fixtures under a random suffix. See the README's new Testing section for
  how to point it at the docker-compose dev database.

Not covered yet: `Reports`/`Accounts`/`Auth`/`Users` have no tests of their
own - this closes the two components PENDING called out as the ones a
hash-chain integrity claim actually depends on, not full coverage.

## 3. No income statement / P&L report

**Done (2026-08-17):** added `Reports.income_statement` (CONSOLE auth,
`web/WEB-INF/validator/income_statement.jschema`) - sums REVENUE/EXPENSE
activity within an optional `[date_from, date_to]` window (both inclusive
whole calendar days; omit either end for life-to-date / through-now),
optionally scoped by `app_id`, and returns revenue/expense line items plus
`revenue_total`/`expense_total`/`net_income`. Unlike `trial_balance`/
`balance_sheet` (cumulative as-of-a-point-in-time balances), this is a
period total, via the new `fetchPeriodAccountBalances` query. Wired up as
an "Income Statement" card in `reports.html`. `balance_sheet` still
excludes REVENUE/EXPENSE (unchanged, no closing-entry mechanism exists) -
that activity now surfaces on the Trial Balance and here instead.

## 4. Accounts can be deactivated but never reactivated

**Done (2026-08-17):** added `Accounts.activate_account` (Admin-only, same
auth tier as `deactivate_account`; `web/WEB-INF/validator/activate_account.jschema`)
- flips `is_active` back to `TRUE`. Confirmed this one should be reversible,
unlike `App.deactivate_app` (which also revokes API keys - irreversible by
design, see the comment on `App.java`): an account has no analogous
credential to revoke, so re-activating doesn't undo anything, it just
re-permits new postings. Wired up as an "Activate" button in the Chart of
Accounts view-account modal in `accounts.html`, mirroring the existing
Deactivate button.

