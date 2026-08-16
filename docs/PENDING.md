# Pending / Known Gaps

Tracked gaps found during a functional-completeness review (2026-08-16) of
the core ledger engine and Ops Console. Not blockers to the soft launch, but
should be closed or explicitly deferred with a reason.

## 1. No self-service password change in the console

`Users.change_own_password` exists as an API function with a validator
schema (`web/WEB-INF/validator/change_own_password.jschema`), but no console
page calls it. Only Admin-driven `reset_password` is wired up in
`users.html`. An Operator has no way to change their own password without
an Admin doing it for them.

**Fix:** add a "change my password" action to the console (e.g. from the
user menu / profile), calling the existing `change_own_password` function.
No backend work needed, UI only.

## 2. No automated test suite

There are no JUnit/test files anywhere in the repo. Correctness of the
hash-chain math, balance validation, reversal maker-checker flow, and report
totals currently rests on manual testing and the reasoning left in code
comments, not on regression tests.

**Fix:** at minimum, unit tests for `HashChain` (canonical payload
determinism, chain continuation) and `Ledger.post_entry`/`reverse_entry`
(balance check, idempotency replay, concurrent-post locking), since these
are the components a hash-chain integrity claim depends on.

## 3. No income statement / P&L report

`Reports.balance_sheet` only totals ASSET/LIABILITY/EQUITY accounts
(documented in `Reports.java` as deliberate: there's no closing-entry
mechanism to fold REVENUE/EXPENSE into Equity). This is a scope decision,
not a bug, but "real-time financial reports" in the README may read as
implying a P&L, which doesn't exist yet.

**Fix:** either scope an income statement endpoint (sum REVENUE/EXPENSE
over a date range) or make the reports section of the README explicit that
only trial balance and balance sheet are currently available.

## 4. Accounts can be deactivated but never reactivated

`Accounts.java` only exposes `deactivate_account`; there is no
`activate_account` function, so a deactivated account is permanently
deactivated. Unconfirmed whether this is intentional.

**Fix:** confirm intent. If accidental, add `activate_account` (Admin-only,
same auth tier as `deactivate_account`).

## 5. Working-tree deletions of reference docs

`docs/BUILD_PLAN.md`, `docs/TSI_Ledger_Integrated_Technical_Design.pdf`, and
`docs/TSI_Ledger_Service_Functional_Specs.pdf` are deleted in the working
tree (unstaged, not committed, not done in any session that touched this
repo's README/design docs). Still recoverable:

```bash
git checkout -- docs/BUILD_PLAN.md "docs/TSI_Ledger_Integrated_Technical_Design.pdf" "docs/TSI_Ledger_Service_Functional_Specs.pdf"
```

**Fix:** confirm whether this was intentional; restore if not.

## Not a gap, deferred by design

- **Triple-entry accounting** — see `docs/TRIPLE_ENTRY_DESIGN.md`. Deliberately
  not implemented or advertised in the README for now.
