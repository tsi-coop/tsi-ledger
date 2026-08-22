package org.tsicoop.ledger.service.v1;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.tsicoop.ledger.framework.Action;
import org.tsicoop.ledger.framework.InputProcessor;
import org.tsicoop.ledger.framework.OutputProcessor;
import org.tsicoop.ledger.framework.PoolDB;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Admin-only App/credential provisioning (CONSOLE auth mode — see
 * InterceptingFilter/_processor.tsi). Each App gets one API_KEY + API_SECRET
 * pair, matching the credential shape other TSI apps use: API_KEY is a
 * non-secret identifier (returned in plaintext any time via list_apps/
 * get_app - safe to display, like a client ID), API_SECRET is the actual
 * bearer credential, generated and shown exactly once at creation or
 * rotation time; only its SHA-256 hash is ever persisted. See
 * InputProcessor.resolveTenantApp for the verification path.
 *
 * can_manage_accounts is a per-App flag an Admin sets at creation (or via
 * update_app afterward) that lets that App call accounts.create_account/
 * deactivate_account/activate_account with its own key pair instead of
 * requiring a console Admin session for every Chart of Accounts change (see
 * Accounts.java / InputProcessor.canManageAccounts).
 */
public class App implements Action {

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        try {
            JSONObject input = InputProcessor.getInput(req);
            String func = (String) input.get("_func");

            // list_apps/get_app are read-only and needed by both roles (e.g. the
            // App filter on the Journal Entries / Reports console pages); every
            // other func here provisions, rotates, or reconfigures a credential
            // and is Admin-only.
            boolean readOnly = "list_apps".equalsIgnoreCase(func) || "get_app".equalsIgnoreCase(func);
            if (!readOnly && !InputProcessor.isAdmin(req)) {
                OutputProcessor.errorResponse(res, 403, "Forbidden", "Admin role required.", req.getRequestURI());
                return;
            }

            switch (func.toLowerCase()) {
                case "create_app": handleCreateApp(input, res, req); break;
                case "list_apps": handleListApps(res, req); break;
                case "get_app": handleGetApp(input, res, req); break;
                case "update_app": handleUpdateApp(input, res, req); break;
                case "rotate_api_key": handleRotateApiKey(input, res, req); break;
                case "revoke_api_key": handleRevokeApiKey(input, res, req); break;
                case "deactivate_app": handleDeactivateApp(input, res, req); break;
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

    private void handleCreateApp(JSONObject input, HttpServletResponse res, HttpServletRequest req) throws SQLException {
        String appName = (String) input.get("app_name");
        boolean canManageAccounts = Boolean.TRUE.equals(input.get("can_manage_accounts"));

        String apiKey = generateToken("key_");
        String apiSecret = generateToken("sk_");
        String apiSecretHash = InputProcessor.sha256Hex(apiSecret);

        PoolDB pool = null; Connection conn = null; PreparedStatement pstmt = null; ResultSet rs = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                    "INSERT INTO apps (app_name, can_manage_accounts, api_key, api_secret_hash) " +
                    "VALUES (?, ?, ?, ?) RETURNING id, is_active, created_at");
            pstmt.setString(1, appName);
            pstmt.setBoolean(2, canManageAccounts);
            pstmt.setString(3, apiKey);
            pstmt.setString(4, apiSecretHash);
            rs = pstmt.executeQuery();
            rs.next();

            JSONObject output = new JSONObject();
            output.put("id", rs.getString("id"));
            output.put("app_name", appName);
            output.put("can_manage_accounts", canManageAccounts);
            output.put("is_active", rs.getBoolean("is_active"));
            output.put("created_at", rs.getTimestamp("created_at").toInstant().toString());
            output.put("api_key", apiKey);
            output.put("api_secret", apiSecret);
            output.put("warning", "Store the API_SECRET now - it is shown only once and cannot be retrieved again. The API_KEY stays visible any time on this page.");
            OutputProcessor.send(res, 201, output);
        } finally {
            if (pool != null) pool.cleanup(rs, pstmt, conn);
        }
    }

    private void handleListApps(HttpServletResponse res, HttpServletRequest req) throws SQLException {
        JSONArray output = new JSONArray();
        PoolDB pool = null; Connection conn = null; PreparedStatement pstmt = null; ResultSet rs = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                    "SELECT id, app_name, can_manage_accounts, is_active, created_at, api_key FROM apps ORDER BY created_at DESC");
            rs = pstmt.executeQuery();
            while (rs.next()) {
                output.add(toAppJson(rs));
            }
            OutputProcessor.send(res, 200, output);
        } finally {
            if (pool != null) pool.cleanup(rs, pstmt, conn);
        }
    }

    private void handleGetApp(JSONObject input, HttpServletResponse res, HttpServletRequest req) throws SQLException {
        UUID appId = UUID.fromString((String) input.get("app_id"));
        PoolDB pool = null; Connection conn = null; PreparedStatement pstmt = null; ResultSet rs = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                    "SELECT id, app_name, can_manage_accounts, is_active, created_at, api_key FROM apps WHERE id = ?");
            pstmt.setObject(1, appId);
            rs = pstmt.executeQuery();
            if (rs.next()) {
                OutputProcessor.send(res, 200, toAppJson(rs));
            } else {
                OutputProcessor.errorResponse(res, 404, "Not Found", "App not found.", req.getRequestURI());
            }
        } finally {
            if (pool != null) pool.cleanup(rs, pstmt, conn);
        }
    }

    private void handleUpdateApp(JSONObject input, HttpServletResponse res, HttpServletRequest req) throws SQLException {
        UUID appId = UUID.fromString((String) input.get("app_id"));
        boolean canManageAccounts = Boolean.TRUE.equals(input.get("can_manage_accounts"));

        PoolDB pool = null; Connection conn = null; PreparedStatement pstmt = null; ResultSet rs = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                    "UPDATE apps SET can_manage_accounts = ? WHERE id = ? " +
                    "RETURNING id, app_name, can_manage_accounts, is_active, created_at, api_key");
            pstmt.setBoolean(1, canManageAccounts);
            pstmt.setObject(2, appId);
            rs = pstmt.executeQuery();
            if (rs.next()) {
                OutputProcessor.send(res, 200, toAppJson(rs));
            } else {
                OutputProcessor.errorResponse(res, 404, "Not Found", "App not found.", req.getRequestURI());
            }
        } finally {
            if (pool != null) pool.cleanup(rs, pstmt, conn);
        }
    }

    private void handleRotateApiKey(JSONObject input, HttpServletResponse res, HttpServletRequest req) throws SQLException {
        UUID appId = UUID.fromString((String) input.get("app_id"));

        String newKey = generateToken("key_");
        String newSecret = generateToken("sk_");
        String newSecretHash = InputProcessor.sha256Hex(newSecret);

        PoolDB pool = null; Connection conn = null; PreparedStatement pstmt = null; ResultSet rs = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            String oldKey = fetchExistingValue(conn, appId, "api_key");

            pstmt = conn.prepareStatement("UPDATE apps SET api_key = ?, api_secret_hash = ? WHERE id = ? RETURNING id");
            pstmt.setString(1, newKey);
            pstmt.setString(2, newSecretHash);
            pstmt.setObject(3, appId);
            rs = pstmt.executeQuery();
            if (!rs.next()) {
                OutputProcessor.errorResponse(res, 404, "Not Found", "App not found.", req.getRequestURI());
                return;
            }

            if (oldKey != null) InputProcessor.evictTenantCache(oldKey);

            JSONObject output = new JSONObject();
            output.put("app_id", appId.toString());
            output.put("api_key", newKey);
            output.put("api_secret", newSecret);
            output.put("warning", "Store the API_SECRET now - it is shown only once and cannot be retrieved again.");
            OutputProcessor.send(res, 200, output);
        } finally {
            if (pool != null) pool.cleanup(rs, pstmt, conn);
        }
    }

    private void handleRevokeApiKey(JSONObject input, HttpServletResponse res, HttpServletRequest req) throws SQLException {
        UUID appId = UUID.fromString((String) input.get("app_id"));

        PoolDB pool = null; Connection conn = null; PreparedStatement pstmt = null; ResultSet rs = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            String oldKey = fetchExistingValue(conn, appId, "api_key");

            pstmt = conn.prepareStatement("UPDATE apps SET api_key = NULL, api_secret_hash = NULL WHERE id = ? RETURNING id");
            pstmt.setObject(1, appId);
            rs = pstmt.executeQuery();
            if (!rs.next()) {
                OutputProcessor.errorResponse(res, 404, "Not Found", "App not found.", req.getRequestURI());
                return;
            }

            if (oldKey != null) InputProcessor.evictTenantCache(oldKey);

            JSONObject output = new JSONObject();
            output.put("app_id", appId.toString());
            output.put("revoked", true);
            OutputProcessor.send(res, 200, output);
        } finally {
            if (pool != null) pool.cleanup(rs, pstmt, conn);
        }
    }

    private void handleDeactivateApp(JSONObject input, HttpServletResponse res, HttpServletRequest req) throws SQLException {
        UUID appId = UUID.fromString((String) input.get("app_id"));

        PoolDB pool = null; Connection conn = null; PreparedStatement pstmt = null; ResultSet rs = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            String key = fetchExistingValue(conn, appId, "api_key");

            pstmt = conn.prepareStatement("UPDATE apps SET is_active = FALSE WHERE id = ? RETURNING id");
            pstmt.setObject(1, appId);
            rs = pstmt.executeQuery();
            if (!rs.next()) {
                OutputProcessor.errorResponse(res, 404, "Not Found", "App not found.", req.getRequestURI());
                return;
            }

            if (key != null) InputProcessor.evictTenantCache(key);

            JSONObject output = new JSONObject();
            output.put("app_id", appId.toString());
            output.put("is_active", false);
            OutputProcessor.send(res, 200, output);
        } finally {
            if (pool != null) pool.cleanup(rs, pstmt, conn);
        }
    }

    private String fetchExistingValue(Connection conn, UUID appId, String column) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement("SELECT " + column + " FROM apps WHERE id = ?")) {
            pstmt.setObject(1, appId);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private JSONObject toAppJson(ResultSet rs) throws SQLException {
        JSONObject app = new JSONObject();
        app.put("id", rs.getString("id"));
        app.put("app_name", rs.getString("app_name"));
        app.put("can_manage_accounts", rs.getBoolean("can_manage_accounts"));
        app.put("is_active", rs.getBoolean("is_active"));
        app.put("created_at", rs.getTimestamp("created_at").toInstant().toString());
        app.put("api_key", rs.getString("api_key"));
        app.put("has_key", rs.getString("api_key") != null);
        return app;
    }

    private static String generateToken(String prefix) {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(prefix);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
