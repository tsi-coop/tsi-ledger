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
 * Admin-only App/API-key provisioning (CONSOLE auth mode — see
 * InterceptingFilter/_processor.tsi). Raw API keys are generated here and
 * returned exactly once, at creation or rotation time; only their SHA-256
 * hash is ever persisted (FSD Section 3 — "secure hash index"). Login/logout
 * moved to Auth.java, which issues a real identity-bearing session instead
 * of trading a shared master key for a cookie.
 */
public class App implements Action {

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        try {
            JSONObject input = InputProcessor.getInput(req);
            String func = (String) input.get("_func");

            // list_apps/get_app are read-only and needed by both roles (e.g. the
            // App filter on the Journal Entries / Reports console pages); every
            // other func here provisions or rotates a credential and is Admin-only.
            boolean readOnly = "list_apps".equalsIgnoreCase(func) || "get_app".equalsIgnoreCase(func);
            if (!readOnly && !InputProcessor.isAdmin(req)) {
                OutputProcessor.errorResponse(res, 403, "Forbidden", "Admin role required.", req.getRequestURI());
                return;
            }

            switch (func.toLowerCase()) {
                case "create_app": handleCreateApp(input, res, req); break;
                case "list_apps": handleListApps(res, req); break;
                case "get_app": handleGetApp(input, res, req); break;
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

        String liveKey = generateApiKey("sk_live_");
        String testKey = generateApiKey("sk_test_");
        String liveHash = InputProcessor.sha256Hex(liveKey);
        String testHash = InputProcessor.sha256Hex(testKey);

        PoolDB pool = null; Connection conn = null; PreparedStatement pstmt = null; ResultSet rs = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                    "INSERT INTO apps (app_name, api_key_live_hash, api_key_test_hash) VALUES (?, ?, ?) " +
                    "RETURNING id, is_active, created_at");
            pstmt.setString(1, appName);
            pstmt.setString(2, liveHash);
            pstmt.setString(3, testHash);
            rs = pstmt.executeQuery();
            rs.next();

            JSONObject output = new JSONObject();
            output.put("id", rs.getString("id"));
            output.put("app_name", appName);
            output.put("is_active", rs.getBoolean("is_active"));
            output.put("created_at", rs.getTimestamp("created_at").toInstant().toString());
            output.put("live_api_key", liveKey);
            output.put("test_api_key", testKey);
            output.put("warning", "Store these keys now - they are shown only once and cannot be retrieved again.");
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
                    "SELECT id, app_name, is_active, created_at, " +
                    "api_key_live_hash IS NOT NULL AS has_live_key, api_key_test_hash IS NOT NULL AS has_test_key " +
                    "FROM apps ORDER BY created_at DESC");
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
                    "SELECT id, app_name, is_active, created_at, " +
                    "api_key_live_hash IS NOT NULL AS has_live_key, api_key_test_hash IS NOT NULL AS has_test_key " +
                    "FROM apps WHERE id = ?");
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

    private void handleRotateApiKey(JSONObject input, HttpServletResponse res, HttpServletRequest req) throws SQLException {
        UUID appId = UUID.fromString((String) input.get("app_id"));
        String keyEnv = normalizeKeyEnv((String) input.get("key_env"), res, req);
        if (keyEnv == null) return;
        String column = "LIVE".equals(keyEnv) ? "api_key_live_hash" : "api_key_test_hash";

        String newKey = generateApiKey("LIVE".equals(keyEnv) ? "sk_live_" : "sk_test_");
        String newHash = InputProcessor.sha256Hex(newKey);

        PoolDB pool = null; Connection conn = null; PreparedStatement pstmt = null; ResultSet rs = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            String oldHash = fetchExistingHash(conn, appId, column);

            pstmt = conn.prepareStatement("UPDATE apps SET " + column + " = ? WHERE id = ? RETURNING id");
            pstmt.setString(1, newHash);
            pstmt.setObject(2, appId);
            rs = pstmt.executeQuery();
            if (!rs.next()) {
                OutputProcessor.errorResponse(res, 404, "Not Found", "App not found.", req.getRequestURI());
                return;
            }

            if (oldHash != null) InputProcessor.evictTenantCache(oldHash);

            JSONObject output = new JSONObject();
            output.put("app_id", appId.toString());
            output.put("key_env", keyEnv);
            output.put("api_key", newKey);
            output.put("warning", "Store this key now - it is shown only once and cannot be retrieved again.");
            OutputProcessor.send(res, 200, output);
        } finally {
            if (pool != null) pool.cleanup(rs, pstmt, conn);
        }
    }

    private void handleRevokeApiKey(JSONObject input, HttpServletResponse res, HttpServletRequest req) throws SQLException {
        UUID appId = UUID.fromString((String) input.get("app_id"));
        String keyEnv = normalizeKeyEnv((String) input.get("key_env"), res, req);
        if (keyEnv == null) return;
        String column = "LIVE".equals(keyEnv) ? "api_key_live_hash" : "api_key_test_hash";

        PoolDB pool = null; Connection conn = null; PreparedStatement pstmt = null; ResultSet rs = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            String oldHash = fetchExistingHash(conn, appId, column);

            pstmt = conn.prepareStatement("UPDATE apps SET " + column + " = NULL WHERE id = ? RETURNING id");
            pstmt.setObject(1, appId);
            rs = pstmt.executeQuery();
            if (!rs.next()) {
                OutputProcessor.errorResponse(res, 404, "Not Found", "App not found.", req.getRequestURI());
                return;
            }

            if (oldHash != null) InputProcessor.evictTenantCache(oldHash);

            JSONObject output = new JSONObject();
            output.put("app_id", appId.toString());
            output.put("key_env", keyEnv);
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

            String liveHash = fetchExistingHash(conn, appId, "api_key_live_hash");
            String testHash = fetchExistingHash(conn, appId, "api_key_test_hash");

            pstmt = conn.prepareStatement("UPDATE apps SET is_active = FALSE WHERE id = ? RETURNING id");
            pstmt.setObject(1, appId);
            rs = pstmt.executeQuery();
            if (!rs.next()) {
                OutputProcessor.errorResponse(res, 404, "Not Found", "App not found.", req.getRequestURI());
                return;
            }

            if (liveHash != null) InputProcessor.evictTenantCache(liveHash);
            if (testHash != null) InputProcessor.evictTenantCache(testHash);

            JSONObject output = new JSONObject();
            output.put("app_id", appId.toString());
            output.put("is_active", false);
            OutputProcessor.send(res, 200, output);
        } finally {
            if (pool != null) pool.cleanup(rs, pstmt, conn);
        }
    }

    private String fetchExistingHash(Connection conn, UUID appId, String column) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement("SELECT " + column + " FROM apps WHERE id = ?")) {
            pstmt.setObject(1, appId);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** key_env is always one of these two literals by the time it's spliced into SQL below — never raw user input. */
    private String normalizeKeyEnv(String raw, HttpServletResponse res, HttpServletRequest req) {
        if ("LIVE".equalsIgnoreCase(raw)) return "LIVE";
        if ("TEST".equalsIgnoreCase(raw)) return "TEST";
        OutputProcessor.errorResponse(res, 400, "Bad Request", "key_env must be LIVE or TEST.", req.getRequestURI());
        return null;
    }

    private JSONObject toAppJson(ResultSet rs) throws SQLException {
        JSONObject app = new JSONObject();
        app.put("id", rs.getString("id"));
        app.put("app_name", rs.getString("app_name"));
        app.put("is_active", rs.getBoolean("is_active"));
        app.put("created_at", rs.getTimestamp("created_at").toInstant().toString());
        app.put("has_live_key", rs.getBoolean("has_live_key"));
        app.put("has_test_key", rs.getBoolean("has_test_key"));
        return app;
    }

    private static String generateApiKey(String prefix) {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(prefix);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
