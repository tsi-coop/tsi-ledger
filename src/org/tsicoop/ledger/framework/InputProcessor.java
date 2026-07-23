package org.tsicoop.ledger.framework;

import com.networknt.schema.ValidationMessage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Enumeration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Request plumbing shared by every Action: body -> JSON, _func schema
 * validation, and the two auth models the ledger uses:
 *  - Console (Ops Console — Users, Apps/API-key provisioning, Chart of
 *    Accounts writes, org-wide ledger/reports/reversal-approval): a JWT
 *    session cookie issued by Auth.login, carrying the operator's user_id/
 *    email/role (see resolveConsoleSession).
 *  - Tenant (App-facing ledger API): X-API-Key hashed with SHA-256 and looked
 *    up directly against apps.api_key_live_hash / api_key_test_hash. This has
 *    to be a deterministic hash (not bcrypt/PasswordHasher) because the FSD
 *    requires a direct index lookup, not a per-row secret comparison.
 */
public class InputProcessor {
    public final static String REQUEST_DATA = "input_json";
    public final static String APP_ID_ATTR = "app_id";
    public final static String KEY_ENV_ATTR = "key_env";
    public final static String USER_ID_ATTR = "user_id";
    public final static String USER_ROLE_ATTR = "user_role";

    private static final String API_KEY_HEADER = "X-API-Key";

    /** Ops Console session cookie set by Auth.login — see InputProcessor.resolveConsoleSession. */
    public static final String SESSION_COOKIE = "tsi_ledger_session";

    private static final long TENANT_CACHE_TTL_MS = 60_000L;

    private static class CachedApp {
        final UUID appId;
        final String keyEnv;
        final boolean active;
        final long expiresAt;
        CachedApp(UUID appId, String keyEnv, boolean active) {
            this.appId = appId;
            this.keyEnv = keyEnv;
            this.active = active;
            this.expiresAt = System.currentTimeMillis() + TENANT_CACHE_TTL_MS;
        }
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }

    private static final ConcurrentHashMap<String, CachedApp> tenantCache = new ConcurrentHashMap<>();

    /** Immediately evicts a rotated/revoked API key hash from the in-process cache. */
    public static void evictTenantCache(String apiKeyHash) {
        tenantCache.remove(apiKeyHash);
    }

    public static String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void processInput(HttpServletRequest request, HttpServletResponse response) {
        StringBuilder buffer = new StringBuilder();
        try {
            BufferedReader reader = request.getReader();
            String line;
            while ((line = reader.readLine()) != null) {
                buffer.append(line);
                buffer.append(System.lineSeparator());
            }

            String data = buffer.toString().trim();

            if (data.isEmpty()) {
                JSONObject jsonParams = new JSONObject();
                Enumeration<String> paramNames = request.getParameterNames();

                while (paramNames.hasMoreElements()) {
                    String name = paramNames.nextElement();
                    String[] values = request.getParameterValues(name);

                    if (values != null && values.length > 0) {
                        if (values.length == 1) {
                            jsonParams.put(name, values[0]);
                        } else {
                            JSONArray valArray = new JSONArray();
                            for (String v : values) valArray.add(v);
                            jsonParams.put(name, valArray);
                        }
                    }
                }
                data = jsonParams.toJSONString();
            }

            request.setAttribute(REQUEST_DATA, data);
        } catch (Exception e) {
            System.err.println("InputProcessor critical failure: " + e.getMessage());
            request.setAttribute(REQUEST_DATA, "{}");
        }
    }

    public static JSONObject getInput(HttpServletRequest req) throws Exception {
        JSONObject input = null;
        try {
            String inputs = (String) req.getAttribute(InputProcessor.REQUEST_DATA);
            if (inputs != null) inputs = inputs.trim();
            input = JacksonUtil.parse(inputs);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return input;
    }

    public static boolean validate(HttpServletRequest req, HttpServletResponse res) {
        JSONObject input;
        Set<ValidationMessage> errors;
        boolean valid = true;
        String func;

        try {
            input = InputProcessor.getInput(req);
            func = (String) input.get("_func");

            if (func == null) {
                OutputProcessor.sendError(res, HttpServletResponse.SC_BAD_REQUEST, "_func missing");
                return false;
            } else {
                errors = JSONSchemaValidator.getHandle().validateSchema(func, input);
            }

            if (errors != null && errors.size() > 0) {
                OutputProcessor.sendError(res, HttpServletResponse.SC_BAD_REQUEST, errors.toString());
                valid = false;
            }
        } catch (Exception e) {
            e.printStackTrace();
            OutputProcessor.sendError(res, HttpServletResponse.SC_BAD_REQUEST, "Unknown input validation error");
            valid = false;
        }
        return valid;
    }

    /**
     * Resolves the X-API-Key header to an active app, hashing it and matching
     * against either the live or test key hash. On success sets APP_ID_ATTR /
     * KEY_ENV_ATTR request attributes and returns true.
     */
    public static boolean resolveTenantApp(HttpServletRequest req) {
        String apiKey = req.getHeader(API_KEY_HEADER);
        if (apiKey == null || apiKey.trim().isEmpty()) return false;

        String hash = sha256Hex(apiKey);
        CachedApp cached = tenantCache.get(hash);
        if (cached != null && !cached.isExpired()) {
            if (!cached.active) return false;
            req.setAttribute(APP_ID_ATTR, cached.appId);
            req.setAttribute(KEY_ENV_ATTR, cached.keyEnv);
            return true;
        }
        tenantCache.remove(hash);

        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                    "SELECT id, is_active, " +
                    "CASE WHEN api_key_live_hash = ? THEN 'LIVE' WHEN api_key_test_hash = ? THEN 'TEST' END AS key_env " +
                    "FROM apps WHERE api_key_live_hash = ? OR api_key_test_hash = ?");
            pstmt.setString(1, hash);
            pstmt.setString(2, hash);
            pstmt.setString(3, hash);
            pstmt.setString(4, hash);
            rs = pstmt.executeQuery();
            if (rs.next()) {
                UUID appId = UUID.fromString(rs.getString("id"));
                boolean active = rs.getBoolean("is_active");
                String keyEnv = rs.getString("key_env");
                tenantCache.put(hash, new CachedApp(appId, keyEnv, active));
                if (!active) return false;
                req.setAttribute(APP_ID_ATTR, appId);
                req.setAttribute(KEY_ENV_ATTR, keyEnv);
                return true;
            }
        } catch (SQLException e) {
            System.err.println("[ERROR] InputProcessor.resolveTenantApp: " + e);
        } finally {
            if (pool != null) pool.cleanup(rs, pstmt, conn);
        }
        return false;
    }

    public static UUID getAppId(HttpServletRequest req) {
        return (UUID) req.getAttribute(APP_ID_ATTR);
    }

    /**
     * Validates the Ops Console session cookie (issued by Auth.login) and, on
     * success, sets USER_ID_ATTR/USER_ROLE_ATTR request attributes — the
     * console-auth analog of resolveTenantApp.
     */
    public static boolean resolveConsoleSession(HttpServletRequest req) {
        jakarta.servlet.http.Cookie[] cookies = req.getCookies();
        if (cookies == null) return false;
        for (jakarta.servlet.http.Cookie cookie : cookies) {
            if (SESSION_COOKIE.equals(cookie.getName())) {
                String token = cookie.getValue();
                if (!JWTUtil.isTokenValid(token)) return false;
                String userId = JWTUtil.getClaim(token, JWTUtil.CLAIM_USER_ID);
                String role = JWTUtil.getClaim(token, JWTUtil.CLAIM_ROLE);
                if (userId == null || role == null) return false;
                req.setAttribute(USER_ID_ATTR, UUID.fromString(userId));
                req.setAttribute(USER_ROLE_ATTR, role);
                return true;
            }
        }
        return false;
    }

    public static UUID getUserId(HttpServletRequest req) {
        return (UUID) req.getAttribute(USER_ID_ATTR);
    }

    public static String getUserRole(HttpServletRequest req) {
        return (String) req.getAttribute(USER_ROLE_ATTR);
    }

    public static boolean isAdmin(HttpServletRequest req) {
        return "ADMIN".equals(getUserRole(req));
    }
}
