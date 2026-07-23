package org.tsicoop.ledger.service.v1;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONObject;
import org.tsicoop.ledger.framework.Action;
import org.tsicoop.ledger.framework.InputProcessor;
import org.tsicoop.ledger.framework.JWTUtil;
import org.tsicoop.ledger.framework.OutputProcessor;
import org.tsicoop.ledger.framework.PasswordHasher;
import org.tsicoop.ledger.framework.PoolDB;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Ops Console session lifecycle (PUBLIC auth mode at the filter — login has
 * nothing to authenticate against yet; logout/me check the session cookie
 * themselves). Replaces v1's App.admin_login/admin_logout, which only ever
 * traded a shared X-Admin-Key for a cookie with no real identity in it.
 */
public class Auth implements Action {

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        try {
            JSONObject input = InputProcessor.getInput(req);
            String func = (String) input.get("_func");

            switch (func.toLowerCase()) {
                case "login": handleLogin(input, res, req); break;
                case "logout": handleLogout(res); break;
                case "me": handleMe(res, req); break;
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

    private void handleLogin(JSONObject input, HttpServletResponse res, HttpServletRequest req) throws SQLException {
        String email = (String) input.get("email");
        String password = (String) input.get("password");
        if (email == null || password == null) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "email and password are required.", req.getRequestURI());
            return;
        }
        email = email.trim().toLowerCase();

        PoolDB pool = null; Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            UUID userId = null; String name = null, role = null, passwordHash = null, status = null;
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "SELECT id, name, role, status, password_hash FROM operators WHERE email = ?")) {
                pstmt.setString(1, email);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        userId = UUID.fromString(rs.getString("id"));
                        name = rs.getString("name");
                        role = rs.getString("role");
                        status = rs.getString("status");
                        passwordHash = rs.getString("password_hash");
                    }
                }
            }

            if (userId == null || !new PasswordHasher().checkPassword(password, passwordHash)) {
                OutputProcessor.errorResponse(res, 401, "Unauthorized", "Invalid email or password.", req.getRequestURI());
                return;
            }
            if (!"ACTIVE".equals(status)) {
                OutputProcessor.errorResponse(res, 403, "Forbidden", "This account has been deactivated.", req.getRequestURI());
                return;
            }

            try (PreparedStatement pstmt = conn.prepareStatement("UPDATE operators SET last_login_at = now() WHERE id = ?")) {
                pstmt.setObject(1, userId);
                pstmt.executeUpdate();
            }

            String token = JWTUtil.generateUserToken(userId, email, role);
            boolean secure = !"local".equals(System.getenv("TSI_LEDGER_ENV"));
            res.addHeader("Set-Cookie", InputProcessor.SESSION_COOKIE + "=" + token
                    + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=36000" + (secure ? "; Secure" : ""));

            JSONObject output = new JSONObject();
            output.put("authenticated", true);
            output.put("user_id", userId.toString());
            output.put("name", name);
            output.put("email", email);
            output.put("role", role);
            OutputProcessor.send(res, 200, output);
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    private void handleLogout(HttpServletResponse res) {
        boolean secure = !"local".equals(System.getenv("TSI_LEDGER_ENV"));
        res.addHeader("Set-Cookie", InputProcessor.SESSION_COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0"
                + (secure ? "; Secure" : ""));
        JSONObject output = new JSONObject();
        output.put("authenticated", false);
        OutputProcessor.send(res, 200, output);
    }

    private void handleMe(HttpServletResponse res, HttpServletRequest req) throws SQLException {
        if (!InputProcessor.resolveConsoleSession(req)) {
            OutputProcessor.errorResponse(res, 401, "Unauthorized", "Not signed in.", req.getRequestURI());
            return;
        }
        UUID userId = InputProcessor.getUserId(req);

        PoolDB pool = null; Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "SELECT id, name, email, role, status FROM operators WHERE id = ?")) {
                pstmt.setObject(1, userId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (!rs.next() || !"ACTIVE".equals(rs.getString("status"))) {
                        OutputProcessor.errorResponse(res, 401, "Unauthorized", "Not signed in.", req.getRequestURI());
                        return;
                    }
                    JSONObject output = new JSONObject();
                    output.put("user_id", rs.getString("id"));
                    output.put("name", rs.getString("name"));
                    output.put("email", rs.getString("email"));
                    output.put("role", rs.getString("role"));
                    OutputProcessor.send(res, 200, output);
                }
            }
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }
}
