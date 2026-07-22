package org.tsicoop.ledger.service.v1;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.tsicoop.ledger.framework.Action;
import org.tsicoop.ledger.framework.InputProcessor;
import org.tsicoop.ledger.framework.OutputProcessor;
import org.tsicoop.ledger.framework.PoolDB;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Tenant-facing (X-API-Key) chart of accounts: create_account, list_accounts,
 * get_account. Every query is scoped to the requesting App's app_id (set by
 * InputProcessor.resolveTenantApp) so one tenant can never see or create
 * accounts against another (FSD Section 1 — Tenant Isolation).
 */
public class Accounts implements Action {

    private static final String UNIQUE_VIOLATION_SQLSTATE = "23505";

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        try {
            JSONObject input = InputProcessor.getInput(req);
            String func = (String) input.get("_func");

            switch (func.toLowerCase()) {
                case "create_account": handleCreateAccount(input, res, req); break;
                case "list_accounts": handleListAccounts(input, res, req); break;
                case "get_account": handleGetAccount(input, res, req); break;
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

    private void handleCreateAccount(JSONObject input, HttpServletResponse res, HttpServletRequest req) throws SQLException {
        UUID appId = InputProcessor.getAppId(req);
        String accountCode = (String) input.get("account_code");
        String accountName = (String) input.get("account_name");
        String accountType = normalizeAccountType((String) input.get("account_type"), res, req);
        if (accountType == null) return;

        PoolDB pool = null; Connection conn = null; PreparedStatement pstmt = null; ResultSet rs = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                    "INSERT INTO accounts (app_id, account_code, account_name, account_type) VALUES (?, ?, ?, ?) " +
                    "RETURNING id, is_active, created_at");
            pstmt.setObject(1, appId);
            pstmt.setString(2, accountCode);
            pstmt.setString(3, accountName);
            pstmt.setString(4, accountType);
            rs = pstmt.executeQuery();
            rs.next();

            JSONObject output = new JSONObject();
            output.put("id", rs.getString("id"));
            output.put("account_code", accountCode);
            output.put("account_name", accountName);
            output.put("account_type", accountType);
            output.put("natural_balance", AccountingRules.naturalBalance(accountType));
            output.put("is_active", rs.getBoolean("is_active"));
            output.put("created_at", rs.getTimestamp("created_at").toInstant().toString());
            OutputProcessor.send(res, 201, output);
        } catch (SQLException e) {
            if (UNIQUE_VIOLATION_SQLSTATE.equals(e.getSQLState())) {
                OutputProcessor.errorResponse(res, 409, "Conflict", "account_code already exists for this app.", req.getRequestURI());
            } else {
                throw e;
            }
        } finally {
            if (pool != null) pool.cleanup(rs, pstmt, conn);
        }
    }

    private void handleListAccounts(JSONObject input, HttpServletResponse res, HttpServletRequest req) throws SQLException {
        UUID appId = InputProcessor.getAppId(req);
        String accountType = input.get("account_type") != null ? normalizeAccountType((String) input.get("account_type"), res, req) : null;
        if (input.get("account_type") != null && accountType == null) return;
        boolean includeInactive = Boolean.TRUE.equals(input.get("include_inactive"));

        StringBuilder sql = new StringBuilder(
                "SELECT id, account_code, account_name, account_type, is_active, created_at FROM accounts WHERE app_id = ?");
        if (accountType != null) sql.append(" AND account_type = ?");
        if (!includeInactive) sql.append(" AND is_active = TRUE");
        sql.append(" ORDER BY account_code");

        JSONArray output = new JSONArray();
        PoolDB pool = null; Connection conn = null; PreparedStatement pstmt = null; ResultSet rs = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(sql.toString());
            pstmt.setObject(1, appId);
            if (accountType != null) pstmt.setString(2, accountType);
            rs = pstmt.executeQuery();
            while (rs.next()) {
                output.add(toAccountJson(rs));
            }
            OutputProcessor.send(res, 200, output);
        } finally {
            if (pool != null) pool.cleanup(rs, pstmt, conn);
        }
    }

    private void handleGetAccount(JSONObject input, HttpServletResponse res, HttpServletRequest req) throws SQLException {
        UUID appId = InputProcessor.getAppId(req);
        UUID accountId = UUID.fromString((String) input.get("account_id"));

        PoolDB pool = null; Connection conn = null; PreparedStatement pstmt = null; ResultSet rs = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                    "SELECT id, account_code, account_name, account_type, is_active, created_at " +
                    "FROM accounts WHERE id = ? AND app_id = ?");
            pstmt.setObject(1, accountId);
            pstmt.setObject(2, appId);
            rs = pstmt.executeQuery();
            if (rs.next()) {
                OutputProcessor.send(res, 200, toAccountJson(rs));
            } else {
                OutputProcessor.errorResponse(res, 404, "Not Found", "Account not found.", req.getRequestURI());
            }
        } finally {
            if (pool != null) pool.cleanup(rs, pstmt, conn);
        }
    }

    private JSONObject toAccountJson(ResultSet rs) throws SQLException {
        String accountType = rs.getString("account_type");
        JSONObject account = new JSONObject();
        account.put("id", rs.getString("id"));
        account.put("account_code", rs.getString("account_code"));
        account.put("account_name", rs.getString("account_name"));
        account.put("account_type", accountType);
        account.put("natural_balance", AccountingRules.naturalBalance(accountType));
        account.put("is_active", rs.getBoolean("is_active"));
        account.put("created_at", rs.getTimestamp("created_at").toInstant().toString());
        return account;
    }

    private String normalizeAccountType(String raw, HttpServletResponse res, HttpServletRequest req) {
        if (raw != null) {
            String upper = raw.toUpperCase();
            switch (upper) {
                case "ASSET": case "LIABILITY": case "EQUITY": case "REVENUE": case "EXPENSE":
                    return upper;
            }
        }
        OutputProcessor.errorResponse(res, 400, "Bad Request",
                "account_type must be one of ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE.", req.getRequestURI());
        return null;
    }
}
