package org.tsicoop.ledger.service.v1;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.tsicoop.ledger.framework.Action;
import org.tsicoop.ledger.framework.InputProcessor;
import org.tsicoop.ledger.framework.OutputProcessor;
import org.tsicoop.ledger.framework.PasswordHasher;
import org.tsicoop.ledger.framework.PoolDB;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Console-only (CONSOLE auth mode) operator management — "the admin should
 * have a user module to configure operators". Every func here except
 * change_own_password requires the ADMIN role; Operators manage only their
 * own password via change_own_password.
 */
public class Users implements Action {

    private static final String UNIQUE_VIOLATION_SQLSTATE = "23505";
    private static final long RESET_KEY_TTL_SECONDS = 24 * 60 * 60;

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        try {
            JSONObject input = InputProcessor.getInput(req);
            String func = (String) input.get("_func");

            if (!"change_own_password".equals(func) && !InputProcessor.isAdmin(req)) {
                OutputProcessor.errorResponse(res, 403, "Forbidden", "Admin role required.", req.getRequestURI());
                return;
            }

            switch (func.toLowerCase()) {
                case "create_operator": handleCreateOperator(input, res, req); break;
                case "list_operators": handleListOperators(res, req); break;
                case "get_operator": handleGetOperator(input, res, req); break;
                case "update_operator": handleUpdateOperator(input, res, req); break;
                case "deactivate_operator": handleDeactivateOperator(input, res, req); break;
                case "reset_password": handleResetPassword(input, res, req); break;
                case "change_own_password": handleChangeOwnPassword(input, res, req); break;
                case "generate_reset_key": handleGenerateResetKey(input, res, req); break;
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

    private void handleCreateOperator(JSONObject input, HttpServletResponse res, HttpServletRequest req) throws SQLException {
        String name = (String) input.get("name");
        String email = normalizeEmail((String) input.get("email"));
        String password = (String) input.get("password");
        String role = normalizeRole((String) input.get("role"), res, req);
        if (role == null) return;
        if (name == null || name.trim().isEmpty() || email == null || password == null || password.length() < 12) {
            OutputProcessor.errorResponse(res, 400, "Bad Request",
                    "name, email, and password (>= 12 chars) are required.", req.getRequestURI());
            return;
        }

        String passwordHash = new PasswordHasher().hashPassword(password);
        PoolDB pool = null; Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "INSERT INTO operators (name, email, password_hash, role) VALUES (?, ?, ?, ?) " +
                    "RETURNING id, status, created_at")) {
                pstmt.setString(1, name.trim());
                pstmt.setString(2, email);
                pstmt.setString(3, passwordHash);
                pstmt.setString(4, role);
                try (ResultSet rs = pstmt.executeQuery()) {
                    rs.next();
                    JSONObject output = new JSONObject();
                    output.put("id", rs.getString("id"));
                    output.put("name", name.trim());
                    output.put("email", email);
                    output.put("role", role);
                    output.put("status", rs.getString("status"));
                    output.put("created_at", rs.getTimestamp("created_at").toInstant().toString());
                    OutputProcessor.send(res, 201, output);
                }
            } catch (SQLException e) {
                if (UNIQUE_VIOLATION_SQLSTATE.equals(e.getSQLState())) {
                    OutputProcessor.errorResponse(res, 409, "Conflict", "An operator with that email already exists.", req.getRequestURI());
                } else {
                    throw e;
                }
            }
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    private void handleListOperators(HttpServletResponse res, HttpServletRequest req) throws SQLException {
        JSONArray output = new JSONArray();
        PoolDB pool = null; Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "SELECT id, name, email, role, status, created_at, last_login_at FROM operators ORDER BY created_at DESC");
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) output.add(toOperatorJson(rs));
            }
            OutputProcessor.send(res, 200, output);
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    private void handleGetOperator(JSONObject input, HttpServletResponse res, HttpServletRequest req) throws SQLException {
        UUID id = UUID.fromString((String) input.get("operator_id"));
        PoolDB pool = null; Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "SELECT id, name, email, role, status, created_at, last_login_at FROM operators WHERE id = ?")) {
                pstmt.setObject(1, id);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        OutputProcessor.send(res, 200, toOperatorJson(rs));
                    } else {
                        OutputProcessor.errorResponse(res, 404, "Not Found", "Operator not found.", req.getRequestURI());
                    }
                }
            }
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    private void handleUpdateOperator(JSONObject input, HttpServletResponse res, HttpServletRequest req) throws SQLException {
        UUID id = UUID.fromString((String) input.get("operator_id"));
        String name = (String) input.get("name");
        String role = normalizeRole((String) input.get("role"), res, req);
        if (role == null) return;

        PoolDB pool = null; Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "UPDATE operators SET name = COALESCE(?, name), role = ?, last_updated_at = now() WHERE id = ? " +
                    "RETURNING id, name, email, role, status, created_at, last_login_at")) {
                pstmt.setString(1, name);
                pstmt.setString(2, role);
                pstmt.setObject(3, id);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        OutputProcessor.send(res, 200, toOperatorJson(rs));
                    } else {
                        OutputProcessor.errorResponse(res, 404, "Not Found", "Operator not found.", req.getRequestURI());
                    }
                }
            }
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    private void handleDeactivateOperator(JSONObject input, HttpServletResponse res, HttpServletRequest req) throws SQLException {
        UUID id = UUID.fromString((String) input.get("operator_id"));
        if (id.equals(InputProcessor.getUserId(req))) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "You cannot deactivate your own account.", req.getRequestURI());
            return;
        }

        PoolDB pool = null; Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "UPDATE operators SET status = 'INACTIVE', last_updated_at = now() WHERE id = ? RETURNING id")) {
                pstmt.setObject(1, id);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (!rs.next()) {
                        OutputProcessor.errorResponse(res, 404, "Not Found", "Operator not found.", req.getRequestURI());
                        return;
                    }
                }
            }
            JSONObject output = new JSONObject();
            output.put("id", id.toString());
            output.put("status", "INACTIVE");
            OutputProcessor.send(res, 200, output);
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    private void handleResetPassword(JSONObject input, HttpServletResponse res, HttpServletRequest req) throws SQLException {
        UUID id = UUID.fromString((String) input.get("operator_id"));
        String newPassword = (String) input.get("new_password");
        if (newPassword == null || newPassword.length() < 12) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "new_password must be >= 12 chars.", req.getRequestURI());
            return;
        }
        updatePasswordHash(res, req, id, newPassword);
    }

    /**
     * Admin-only: mints a one-time reset key for an Operator locked out of
     * their account, so the Admin can share it out of band without ever
     * having to know or choose the Operator's new password themselves. Only
     * the SHA-256 hash is persisted (see Auth.reset_with_key, which redeems
     * it); the raw key is returned exactly once, here.
     */
    private void handleGenerateResetKey(JSONObject input, HttpServletResponse res, HttpServletRequest req) throws SQLException {
        UUID id = UUID.fromString((String) input.get("operator_id"));
        String rawKey = generateResetKey();
        String keyHash = InputProcessor.sha256Hex(rawKey);
        Timestamp expiresAt = Timestamp.from(Instant.now().plusSeconds(RESET_KEY_TTL_SECONDS));

        PoolDB pool = null; Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "UPDATE operators SET reset_key_hash = ?, reset_key_expires_at = ? WHERE id = ? " +
                    "RETURNING id, email")) {
                pstmt.setString(1, keyHash);
                pstmt.setTimestamp(2, expiresAt);
                pstmt.setObject(3, id);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (!rs.next()) {
                        OutputProcessor.errorResponse(res, 404, "Not Found", "Operator not found.", req.getRequestURI());
                        return;
                    }
                    JSONObject output = new JSONObject();
                    output.put("id", rs.getString("id"));
                    output.put("email", rs.getString("email"));
                    output.put("reset_key", rawKey);
                    output.put("expires_at", expiresAt.toInstant().toString());
                    output.put("warning", "Shown once - share it with the operator through a secure channel now. It cannot be retrieved again.");
                    OutputProcessor.send(res, 200, output);
                }
            }
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    /** Human-typeable (Crockford-ish base32) reset key - short enough to read over a call, long enough (2^80 space) to resist guessing within the TTL. */
    private static String generateResetKey() {
        String symbols = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(20);
        for (int i = 0; i < 16; i++) {
            if (i > 0 && i % 4 == 0) sb.append('-');
            sb.append(symbols.charAt(random.nextInt(symbols.length())));
        }
        return sb.toString();
    }

    private void handleChangeOwnPassword(JSONObject input, HttpServletResponse res, HttpServletRequest req) throws SQLException {
        if (!InputProcessor.resolveConsoleSession(req)) {
            OutputProcessor.errorResponse(res, 401, "Unauthorized", "Not signed in.", req.getRequestURI());
            return;
        }
        String currentPassword = (String) input.get("current_password");
        String newPassword = (String) input.get("new_password");
        if (newPassword == null || newPassword.length() < 12) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "new_password must be >= 12 chars.", req.getRequestURI());
            return;
        }
        UUID id = InputProcessor.getUserId(req);

        PoolDB pool = null; Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            String existingHash;
            try (PreparedStatement pstmt = conn.prepareStatement("SELECT password_hash FROM operators WHERE id = ?")) {
                pstmt.setObject(1, id);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (!rs.next()) { OutputProcessor.errorResponse(res, 404, "Not Found", "Operator not found.", req.getRequestURI()); return; }
                    existingHash = rs.getString("password_hash");
                }
            }
            if (currentPassword == null || !new PasswordHasher().checkPassword(currentPassword, existingHash)) {
                OutputProcessor.errorResponse(res, 401, "Unauthorized", "Current password is incorrect.", req.getRequestURI());
                return;
            }
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
        updatePasswordHash(res, req, id, newPassword);
    }

    private void updatePasswordHash(HttpServletResponse res, HttpServletRequest req, UUID id, String newPassword) throws SQLException {
        String newHash = new PasswordHasher().hashPassword(newPassword);
        PoolDB pool = null; Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "UPDATE operators SET password_hash = ?, last_updated_at = now() WHERE id = ? RETURNING id")) {
                pstmt.setString(1, newHash);
                pstmt.setObject(2, id);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (!rs.next()) {
                        OutputProcessor.errorResponse(res, 404, "Not Found", "Operator not found.", req.getRequestURI());
                        return;
                    }
                }
            }
            JSONObject output = new JSONObject();
            output.put("id", id.toString());
            output.put("password_reset", true);
            OutputProcessor.send(res, 200, output);
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    private JSONObject toOperatorJson(ResultSet rs) throws SQLException {
        JSONObject o = new JSONObject();
        o.put("id", rs.getString("id"));
        o.put("name", rs.getString("name"));
        o.put("email", rs.getString("email"));
        o.put("role", rs.getString("role"));
        o.put("status", rs.getString("status"));
        o.put("created_at", rs.getTimestamp("created_at").toInstant().toString());
        o.put("last_login_at", rs.getTimestamp("last_login_at") != null ? rs.getTimestamp("last_login_at").toInstant().toString() : null);
        return o;
    }

    private static String normalizeEmail(String raw) {
        return raw == null ? null : raw.trim().toLowerCase();
    }

    private String normalizeRole(String raw, HttpServletResponse res, HttpServletRequest req) {
        if ("ADMIN".equalsIgnoreCase(raw)) return "ADMIN";
        if ("OPERATOR".equalsIgnoreCase(raw)) return "OPERATOR";
        OutputProcessor.errorResponse(res, 400, "Bad Request", "role must be ADMIN or OPERATOR.", req.getRequestURI());
        return null;
    }
}
