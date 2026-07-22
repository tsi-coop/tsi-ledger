package org.tsicoop.ledger.service.v1;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.tsicoop.ledger.framework.Action;
import org.tsicoop.ledger.framework.InputProcessor;
import org.tsicoop.ledger.framework.OutputProcessor;
import org.tsicoop.ledger.framework.PoolDB;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Financial reporting: trial_balance, balance_sheet (FSD Section 6).
 * Both reports derive entirely from journal_entries/entry_lines — the
 * immutable transaction log — never from a running-balance column, so
 * they're only as trustworthy as the hash chain verify_audit checks.
 *
 * balance_sheet totals only ASSET/LIABILITY/EQUITY accounts, matching FSD
 * 6.2's formula (Assets = Liabilities + Equity) literally — REVENUE/EXPENSE
 * feed an income statement, which the FSD doesn't specify an endpoint for,
 * and there's no closing-entry mechanism defined anywhere to fold them into
 * Equity. trial_balance, by contrast, lists every account.
 */
public class Reports implements Action {

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        try {
            JSONObject input = InputProcessor.getInput(req);
            String func = (String) input.get("_func");

            switch (func.toLowerCase()) {
                case "trial_balance": handleTrialBalance(input, res, req); break;
                case "balance_sheet": handleBalanceSheet(input, res, req); break;
                default:
                    OutputProcessor.errorResponse(res, 400, "Bad Request", "Unknown function: " + func, req.getRequestURI());
            }
        } catch (Exception e) {
            OutputProcessor.errorResponse(res, 500, "Internal Error", e.getMessage(), req.getRequestURI());
        }
    }

    @Override
    public boolean validate(String method, HttpServletRequest req, HttpServletResponse res) {
        return true;
    }

    private static class AccountBalance {
        String accountCode;
        String accountName;
        String accountType;
        BigDecimal debitTotal;
        BigDecimal creditTotal;
    }

    private void handleTrialBalance(JSONObject input, HttpServletResponse res, HttpServletRequest req) throws Exception {
        UUID appId = InputProcessor.getAppId(req);
        String asOfDateRaw = (String) input.get("as_of_date");
        Instant asOfExclusiveUpper = parseAsOfDateUpperBound(asOfDateRaw);
        if (asOfDateRaw != null && asOfExclusiveUpper == null) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "as_of_date must be a valid calendar date (YYYY-MM-DD).", req.getRequestURI());
            return;
        }

        PoolDB pool = null; Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            List<AccountBalance> balances = fetchAccountBalances(conn, appId, asOfExclusiveUpper);

            JSONArray accounts = new JSONArray();
            BigDecimal totalDebits = BigDecimal.ZERO;
            BigDecimal totalCredits = BigDecimal.ZERO;
            for (AccountBalance b : balances) {
                JSONObject row = new JSONObject();
                row.put("account_code", b.accountCode);
                row.put("account_name", b.accountName);
                row.put("account_type", b.accountType);
                row.put("debit_total", b.debitTotal);
                row.put("credit_total", b.creditTotal);
                accounts.add(row);
                totalDebits = totalDebits.add(b.debitTotal);
                totalCredits = totalCredits.add(b.creditTotal);
            }

            JSONObject output = new JSONObject();
            output.put("as_of_date", asOfDateRaw);
            output.put("accounts", accounts);
            output.put("total_debits", totalDebits);
            output.put("total_credits", totalCredits);
            output.put("balanced", totalDebits.compareTo(totalCredits) == 0);
            OutputProcessor.send(res, 200, output);
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    private void handleBalanceSheet(JSONObject input, HttpServletResponse res, HttpServletRequest req) throws Exception {
        UUID appId = InputProcessor.getAppId(req);
        String asOfDateRaw = (String) input.get("as_of_date");
        Instant asOfExclusiveUpper = parseAsOfDateUpperBound(asOfDateRaw);
        if (asOfDateRaw != null && asOfExclusiveUpper == null) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "as_of_date must be a valid calendar date (YYYY-MM-DD).", req.getRequestURI());
            return;
        }

        PoolDB pool = null; Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            List<AccountBalance> balances = fetchAccountBalances(conn, appId, asOfExclusiveUpper);

            JSONArray assets = new JSONArray();
            JSONArray liabilities = new JSONArray();
            JSONArray equity = new JSONArray();
            BigDecimal assetsTotal = BigDecimal.ZERO;
            BigDecimal liabilitiesTotal = BigDecimal.ZERO;
            BigDecimal equityTotal = BigDecimal.ZERO;

            for (AccountBalance b : balances) {
                if (!"ASSET".equals(b.accountType) && !"LIABILITY".equals(b.accountType) && !"EQUITY".equals(b.accountType)) continue;

                boolean naturalDebit = "DEBIT".equals(AccountingRules.naturalBalance(b.accountType));
                BigDecimal balance = naturalDebit ? b.debitTotal.subtract(b.creditTotal) : b.creditTotal.subtract(b.debitTotal);

                JSONObject row = new JSONObject();
                row.put("account_code", b.accountCode);
                row.put("account_name", b.accountName);
                row.put("balance", balance);

                switch (b.accountType) {
                    case "ASSET": assets.add(row); assetsTotal = assetsTotal.add(balance); break;
                    case "LIABILITY": liabilities.add(row); liabilitiesTotal = liabilitiesTotal.add(balance); break;
                    case "EQUITY": equity.add(row); equityTotal = equityTotal.add(balance); break;
                }
            }

            JSONObject output = new JSONObject();
            output.put("as_of_date", asOfDateRaw);
            output.put("assets", assets);
            output.put("liabilities", liabilities);
            output.put("equity", equity);
            output.put("assets_total", assetsTotal);
            output.put("liabilities_total", liabilitiesTotal);
            output.put("equity_total", equityTotal);
            output.put("balanced", assetsTotal.compareTo(liabilitiesTotal.add(equityTotal)) == 0);
            OutputProcessor.send(res, 200, output);
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    /** Per-account sum of debit/credit legs posted up to (exclusive of) asOfExclusiveUpper, or all time if null. */
    private List<AccountBalance> fetchAccountBalances(Connection conn, UUID appId, Instant asOfExclusiveUpper) throws SQLException {
        String sql =
                "SELECT a.account_code, a.account_name, a.account_type, " +
                "COALESCE(SUM(CASE WHEN v.line_type = 'DEBIT' THEN v.amount ELSE 0 END), 0) AS debit_total, " +
                "COALESCE(SUM(CASE WHEN v.line_type = 'CREDIT' THEN v.amount ELSE 0 END), 0) AS credit_total " +
                "FROM accounts a " +
                "LEFT JOIN (" +
                "  SELECT el.account_id, el.line_type, el.amount FROM entry_lines el " +
                "  JOIN journal_entries je ON je.id = el.entry_id " +
                "  WHERE je.app_id = ?" + (asOfExclusiveUpper != null ? " AND je.posted_at < ?" : "") +
                ") v ON v.account_id = a.id " +
                "WHERE a.app_id = ? " +
                "GROUP BY a.id, a.account_code, a.account_name, a.account_type " +
                "ORDER BY a.account_code";

        List<AccountBalance> balances = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            int i = 1;
            pstmt.setObject(i++, appId);
            if (asOfExclusiveUpper != null) pstmt.setTimestamp(i++, Timestamp.from(asOfExclusiveUpper));
            pstmt.setObject(i, appId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    AccountBalance b = new AccountBalance();
                    b.accountCode = rs.getString("account_code");
                    b.accountName = rs.getString("account_name");
                    b.accountType = rs.getString("account_type");
                    b.debitTotal = rs.getBigDecimal("debit_total");
                    b.creditTotal = rs.getBigDecimal("credit_total");
                    balances.add(b);
                }
            }
        }
        return balances;
    }

    /** as_of_date is a calendar date; the upper bound is the start of the *next* day UTC (exclusive), so the whole named day is included. */
    private static Instant parseAsOfDateUpperBound(String raw) {
        if (raw == null) return null;
        try {
            return LocalDate.parse(raw).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
