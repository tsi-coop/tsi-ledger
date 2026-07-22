package org.tsicoop.ledger.service.v1;

/**
 * FSD Section 2's Account Classifications & Natural Balances table — single
 * source of truth, shared by Accounts (display) and Reports (balance-sheet
 * direction math) rather than duplicated in each.
 */
public class AccountingRules {

    public static String naturalBalance(String accountType) {
        switch (accountType) {
            case "ASSET":
            case "EXPENSE":
                return "DEBIT";
            case "LIABILITY":
            case "EQUITY":
            case "REVENUE":
                return "CREDIT";
            default:
                throw new IllegalStateException("Unknown account_type: " + accountType);
        }
    }

    private AccountingRules() {}
}
