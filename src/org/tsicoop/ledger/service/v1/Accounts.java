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
 * Chart of Accounts — ONE table, shared by the whole organization (TENANT_OR_
 * CONSOLE auth mode). create_account/deactivate_account/activate_account
 * require either the console Admin role, or a tenant App whose
 * can_manage_accounts flag is set (InputProcessor.canManageAccounts) — the COA
 * is structural, organization-wide data, so writing to it is still a
 * deliberately granted privilege, not something every App or Operator gets by
 * default. list_accounts/get_account stay readable either way — any App may
 * still validate account codes programmatically via its own key pair, and
 * console Operators can browse read-only.
 *
 * activate_account exists because deactivation here is reversible, unlike
 * App.deactivate_app (which also revokes API keys — those can't be
 * un-revoked, so that deactivation is deliberately one-way). An account has
 * nothing analogous to revoke; flipping is_active back on doesn't undo or
 * hide any history, it just re-permits new postings against it.
 */
public class Accounts implements Action {

    private static final String UNIQUE_VIOLATION_SQLSTATE = "23505";

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        try {
            JSONObject input = InputProcessor.getInput(req);
            String func = (String) input.get("_func");

            boolean write = "create_account".equalsIgnoreCase(func) || "deactivate_account".equalsIgnoreCase(func)
                    || "activate_account".equalsIgnoreCase(func);
            if (write && !InputProcessor.canManageAccounts(req)) {
                OutputProcessor.errorResponse(res, 403, "Forbidden",
                        "Admin role required, or an App with can_manage_accounts=true.", req.getRequestURI());
                return;
            }

            switch (func.toLowerCase()) {
                case "create_account": handleCreateAccount(input, res, req); break;
                case "list_accounts": handleListAccounts(input, res, req); break;
                case "get_account": handleGetAccount(input, res, req); break;
                case "deactivate_account": handleDeactivateAccount(input, res, req); break;
                case "activate_account": handleActivateAccount(input, res, req); break;
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
        String accountCode = (String) input.get("account_code");
        String accountName = ((String) input.get("account_name")).trim();
        String accountType = normalizeAccountType((String) input.get("account_type"), res, req);
        if (accountType == null) return;
        UUID createdBy = InputProcessor.getUserId(req);

        PoolDB pool = null; Connection conn = null; PreparedStatement pstmt = null; ResultSet rs = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            // Pre-check for a friendlier message than the generic unique-violation catch below -
            // two accounts sharing a name (case-insensitive) under different codes would otherwise
            // silently split one real balance across two ledger lines.
            String clashingCode = findAccountCodeByNameCaseInsensitive(conn, accountName);
            if (clashingCode != null) {
                OutputProcessor.errorResponse(res, 409, "Conflict",
                        "An account named '" + accountName + "' already exists (account_code " + clashingCode + ").", req.getRequestURI());
                return;
            }

            pstmt = conn.prepareStatement(
                    "INSERT INTO accounts (account_code, account_name, account_type, created_by_user_id) VALUES (?, ?, ?, ?) " +
                    "RETURNING id, is_active, created_at");
            pstmt.setString(1, accountCode);
            pstmt.setString(2, accountName);
            pstmt.setString(3, accountType);
            pstmt.setObject(4, createdBy);
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
                OutputProcessor.errorResponse(res, 409, "Conflict", "account_code or account_name already exists.", req.getRequestURI());
            } else {
                throw e;
            }
        } finally {
            if (pool != null) pool.cleanup(rs, pstmt, conn);
        }
    }

    private String findAccountCodeByNameCaseInsensitive(Connection conn, String accountName) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement("SELECT account_code FROM accounts WHERE LOWER(account_name) = LOWER(?)")) {
            pstmt.setString(1, accountName);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next() ? rs.getString("account_code") : null;
            }
        }
    }

    private void handleListAccounts(JSONObject input, HttpServletResponse res, HttpServletRequest req) throws SQLException {
        String accountType = input.get("account_type") != null ? normalizeAccountType((String) input.get("account_type"), res, req) : null;
        if (input.get("account_type") != null && accountType == null) return;
        boolean includeInactive = Boolean.TRUE.equals(input.get("include_inactive"));

        StringBuilder sql = new StringBuilder("SELECT id, account_code, account_name, account_type, is_active, created_at FROM accounts WHERE 1=1");
        if (accountType != null) sql.append(" AND account_type = ?");
        if (!includeInactive) sql.append(" AND is_active = TRUE");
        sql.append(" ORDER BY account_code");

        JSONArray output = new JSONArray();
        PoolDB pool = null; Connection conn = null; PreparedStatement pstmt = null; ResultSet rs = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(sql.toString());
            if (accountType != null) pstmt.setString(1, accountType);
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
        UUID accountId = UUID.fromString((String) input.get("account_id"));

        PoolDB pool = null; Connection conn = null; PreparedStatement pstmt = null; ResultSet rs = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                    "SELECT id, account_code, account_name, account_type, is_active, created_at FROM accounts WHERE id = ?");
            pstmt.setObject(1, accountId);
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

    private void handleDeactivateAccount(JSONObject input, HttpServletResponse res, HttpServletRequest req) throws SQLException {
        UUID accountId = UUID.fromString((String) input.get("account_id"));

        PoolDB pool = null; Connection conn = null; PreparedStatement pstmt = null; ResultSet rs = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement("UPDATE accounts SET is_active = FALSE WHERE id = ? RETURNING id");
            pstmt.setObject(1, accountId);
            rs = pstmt.executeQuery();
            if (!rs.next()) {
                OutputProcessor.errorResponse(res, 404, "Not Found", "Account not found.", req.getRequestURI());
                return;
            }
            JSONObject output = new JSONObject();
            output.put("id", accountId.toString());
            output.put("is_active", false);
            OutputProcessor.send(res, 200, output);
        } finally {
            if (pool != null) pool.cleanup(rs, pstmt, conn);
        }
    }

    private void handleActivateAccount(JSONObject input, HttpServletResponse res, HttpServletRequest req) throws SQLException {
        UUID accountId = UUID.fromString((String) input.get("account_id"));

        PoolDB pool = null; Connection conn = null; PreparedStatement pstmt = null; ResultSet rs = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement("UPDATE accounts SET is_active = TRUE WHERE id = ? RETURNING id");
            pstmt.setObject(1, accountId);
            rs = pstmt.executeQuery();
            if (!rs.next()) {
                OutputProcessor.errorResponse(res, 404, "Not Found", "Account not found.", req.getRequestURI());
                return;
            }
            JSONObject output = new JSONObject();
            output.put("id", accountId.toString());
            output.put("is_active", true);
            OutputProcessor.send(res, 200, output);
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
