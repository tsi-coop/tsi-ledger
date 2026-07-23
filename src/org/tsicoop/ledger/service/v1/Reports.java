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
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Financial reporting (CONSOLE-only auth mode): trial_balance, balance_sheet,
 * export_entries (FSD Section 6 + Phase 2 raw export). Org-wide by default —
 * "one gigantic accounting system for the organization" means these reports
 * combine every App's postings against the shared Chart of Accounts unless
 * an app_id filter narrows to one App. Reports moved off tenant-API-key
 * access entirely in Phase 2 (v1 had per-tenant self-service reports) — this
 * is now purely an accounts-team console function.
 *
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
                case "export_entries": handleExportEntries(input, res, req); break;
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
        String asOfDateRaw = (String) input.get("as_of_date");
        Instant asOfExclusiveUpper = parseAsOfDateUpperBound(asOfDateRaw);
        if (asOfDateRaw != null && asOfExclusiveUpper == null) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "as_of_date must be a valid calendar date (YYYY-MM-DD).", req.getRequestURI());
            return;
        }
        UUID appIdFilter = parseOptionalAppId(input, res, req);
        if (input.get("app_id") != null && appIdFilter == null) return;

        PoolDB pool = null; Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            List<AccountBalance> balances = fetchAccountBalances(conn, appIdFilter, asOfExclusiveUpper);

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
            output.put("app_id", appIdFilter != null ? appIdFilter.toString() : null);
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
        String asOfDateRaw = (String) input.get("as_of_date");
        Instant asOfExclusiveUpper = parseAsOfDateUpperBound(asOfDateRaw);
        if (asOfDateRaw != null && asOfExclusiveUpper == null) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "as_of_date must be a valid calendar date (YYYY-MM-DD).", req.getRequestURI());
            return;
        }
        UUID appIdFilter = parseOptionalAppId(input, res, req);
        if (input.get("app_id") != null && appIdFilter == null) return;

        PoolDB pool = null; Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            List<AccountBalance> balances = fetchAccountBalances(conn, appIdFilter, asOfExclusiveUpper);

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
            output.put("app_id", appIdFilter != null ? appIdFilter.toString() : null);
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

    /** Raw journal-entries CSV dump — one row per entry_lines leg, joined to its parent entry/account/app. */
    private void handleExportEntries(JSONObject input, HttpServletResponse res, HttpServletRequest req) throws Exception {
        UUID appIdFilter = parseOptionalAppId(input, res, req);
        if (input.get("app_id") != null && appIdFilter == null) return;

        Instant dateFrom = parseFlexibleInstant((String) input.get("date_from"));
        Instant dateTo = parseFlexibleInstant((String) input.get("date_to"));

        StringBuilder sql = new StringBuilder(
                "SELECT je.id AS entry_id, je.sequence_number, je.app_id, ap.app_name, je.entry_type, je.reverses_entry_id, " +
                "je.posted_at, je.reference_id, je.description, a.account_code, a.account_name, el.line_type, el.amount, je.block_hash " +
                "FROM entry_lines el " +
                "JOIN journal_entries je ON je.id = el.entry_id " +
                "JOIN apps ap ON ap.id = je.app_id " +
                "JOIN accounts a ON a.id = el.account_id WHERE 1=1");
        if (appIdFilter != null) sql.append(" AND je.app_id = ?");
        if (dateFrom != null) sql.append(" AND je.posted_at >= ?");
        if (dateTo != null) sql.append(" AND je.posted_at <= ?");
        sql.append(" ORDER BY je.app_id, je.sequence_number, a.account_code");

        List<String> header = Arrays.asList("entry_id", "sequence_number", "app_id", "app_name", "entry_type",
                "reverses_entry_id", "posted_at", "reference_id", "description", "account_code", "account_name",
                "line_type", "amount", "block_hash");
        List<List<Object>> rows = new ArrayList<>();

        PoolDB pool = null; Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
                int i = 1;
                if (appIdFilter != null) pstmt.setObject(i++, appIdFilter);
                if (dateFrom != null) pstmt.setTimestamp(i++, Timestamp.from(dateFrom));
                if (dateTo != null) pstmt.setTimestamp(i++, Timestamp.from(dateTo));
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        rows.add(Arrays.asList(
                                rs.getString("entry_id"), rs.getLong("sequence_number"), rs.getString("app_id"),
                                rs.getString("app_name"), rs.getString("entry_type"), rs.getString("reverses_entry_id"),
                                rs.getTimestamp("posted_at").toInstant().toString(), rs.getString("reference_id"),
                                rs.getString("description"), rs.getString("account_code"), rs.getString("account_name"),
                                rs.getString("line_type"), rs.getBigDecimal("amount"), rs.getString("block_hash")));
                    }
                }
            }
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }

        String filename = "journal_entries_" + LocalDate.now(ZoneOffset.UTC) + ".csv";
        OutputProcessor.sendCsv(res, filename, header, rows);
    }

    /** Per-account sum of debit/credit legs posted up to (exclusive of) asOfExclusiveUpper, optionally
     * scoped to one App — org-wide (all Apps combined against the shared COA) when appIdFilter is null. */
    private List<AccountBalance> fetchAccountBalances(Connection conn, UUID appIdFilter, Instant asOfExclusiveUpper) throws SQLException {
        String sql =
                "SELECT a.account_code, a.account_name, a.account_type, " +
                "COALESCE(SUM(CASE WHEN v.line_type = 'DEBIT' THEN v.amount ELSE 0 END), 0) AS debit_total, " +
                "COALESCE(SUM(CASE WHEN v.line_type = 'CREDIT' THEN v.amount ELSE 0 END), 0) AS credit_total " +
                "FROM accounts a " +
                "LEFT JOIN (" +
                "  SELECT el.account_id, el.line_type, el.amount FROM entry_lines el " +
                "  JOIN journal_entries je ON je.id = el.entry_id " +
                "  WHERE 1=1" + (appIdFilter != null ? " AND je.app_id = ?" : "") +
                (asOfExclusiveUpper != null ? " AND je.posted_at < ?" : "") +
                ") v ON v.account_id = a.id " +
                "GROUP BY a.id, a.account_code, a.account_name, a.account_type " +
                "ORDER BY a.account_code";

        List<AccountBalance> balances = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            int i = 1;
            if (appIdFilter != null) pstmt.setObject(i++, appIdFilter);
            if (asOfExclusiveUpper != null) pstmt.setTimestamp(i++, Timestamp.from(asOfExclusiveUpper));
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

    private UUID parseOptionalAppId(JSONObject input, HttpServletResponse res, HttpServletRequest req) {
        Object raw = input.get("app_id");
        if (raw == null) return null;
        try {
            return UUID.fromString((String) raw);
        } catch (Exception e) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "app_id must be a UUID.", req.getRequestURI());
            return null;
        }
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

    /** date_from/date_to on export_entries accept either a calendar date (YYYY-MM-DD) or a full ISO-8601 instant. */
    private static Instant parseFlexibleInstant(String raw) {
        if (raw == null) return null;
        try {
            return LocalDate.parse(raw).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException e1) {
            try {
                return Instant.parse(raw);
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }
}
