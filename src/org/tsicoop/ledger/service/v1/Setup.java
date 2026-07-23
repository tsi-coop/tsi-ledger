package org.tsicoop.ledger.service.v1;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONObject;
import org.tsicoop.ledger.framework.Action;
import org.tsicoop.ledger.framework.InputProcessor;
import org.tsicoop.ledger.framework.OutputProcessor;
import org.tsicoop.ledger.framework.PasswordHasher;
import org.tsicoop.ledger.framework.PoolDB;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * One-time bootstrap (PUBLIC auth mode — see InterceptingFilter/_processor.tsi):
 * creates the very first ADMIN operator so there's someone who can log in and
 * create everyone else. initial_setup 409s once any operator row exists —
 * after that, account creation only happens via Users (Admin-only, CONSOLE).
 * setup_status is a no-auth read so login.html can redirect first-time
 * visitors here automatically instead of showing a login form with nothing
 * to log into.
 */
public class Setup implements Action {

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        try {
            JSONObject input = InputProcessor.getInput(req);
            String func = (String) input.get("_func");

            switch (func.toLowerCase()) {
                case "initial_setup": handleInitialSetup(input, res, req); break;
                case "setup_status": handleSetupStatus(res, req); break;
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

    private void handleInitialSetup(JSONObject input, HttpServletResponse res, HttpServletRequest req) throws SQLException {
        String name = (String) input.get("name");
        String email = normalizeEmail((String) input.get("email"));
        String password = (String) input.get("password");

        if (name == null || name.trim().isEmpty() || email == null || password == null || password.length() < 12) {
            OutputProcessor.errorResponse(res, 400, "Bad Request",
                    "name, email, and password (>= 12 chars) are required.", req.getRequestURI());
            return;
        }

        PoolDB pool = null; Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            if (operatorCountAtLeastOne(conn)) {
                OutputProcessor.errorResponse(res, 409, "Conflict",
                        "System is already configured. Sign in and use the Users module instead.", req.getRequestURI());
                return;
            }

            String passwordHash = new PasswordHasher().hashPassword(password);
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "INSERT INTO operators (name, email, password_hash, role) VALUES (?, ?, ?, 'ADMIN') " +
                    "RETURNING id, created_at")) {
                pstmt.setString(1, name.trim());
                pstmt.setString(2, email);
                pstmt.setString(3, passwordHash);
                try (ResultSet rs = pstmt.executeQuery()) {
                    rs.next();
                    JSONObject output = new JSONObject();
                    output.put("id", rs.getString("id"));
                    output.put("name", name.trim());
                    output.put("email", email);
                    output.put("role", "ADMIN");
                    output.put("created_at", rs.getTimestamp("created_at").toInstant().toString());
                    OutputProcessor.send(res, 201, output);
                }
            } catch (SQLException e) {
                if ("23505".equals(e.getSQLState())) {
                    OutputProcessor.errorResponse(res, 409, "Conflict", "System is already configured.", req.getRequestURI());
                } else {
                    throw e;
                }
            }
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    private void handleSetupStatus(HttpServletResponse res, HttpServletRequest req) throws SQLException {
        PoolDB pool = null; Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            JSONObject output = new JSONObject();
            output.put("configured", operatorCountAtLeastOne(conn));
            OutputProcessor.send(res, 200, output);
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    private boolean operatorCountAtLeastOne(Connection conn) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement("SELECT 1 FROM operators LIMIT 1");
             ResultSet rs = pstmt.executeQuery()) {
            return rs.next();
        }
    }

    private static String normalizeEmail(String raw) {
        return raw == null ? null : raw.trim().toLowerCase();
    }
}
