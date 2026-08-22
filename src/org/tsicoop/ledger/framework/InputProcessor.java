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
 *  - Tenant (App-facing ledger API): an API_KEY + API_SECRET pair, like most
 *    other TSI apps. X-API-Key is a non-secret identifier - looked up
 *    directly (plaintext, indexed) against apps.api_key - and X-API-Secret is
 *    hashed with SHA-256 and compared against apps.api_secret_hash. This has
 *    to be a deterministic hash (not bcrypt/PasswordHasher) because the
 *    lookup is by key first, then a single hash comparison, not a per-row
 *    secret scan.
 */
public class InputProcessor {
    public final static String REQUEST_DATA = "input_json";
    public final static String APP_ID_ATTR = "app_id";
    public final static String CAN_MANAGE_ACCOUNTS_ATTR = "can_manage_accounts";
    public final static String USER_ID_ATTR = "user_id";
    public final static String USER_ROLE_ATTR = "user_role";

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String API_SECRET_HEADER = "X-API-Secret";

    /** Ops Console session cookie set by Auth.login — see InputProcessor.resolveConsoleSession. */
    public static final String SESSION_COOKIE = "tsi_ledger_session";

    private static final long TENANT_CACHE_TTL_MS = 60_000L;

    private static class CachedApp {
        final UUID appId;
        final String secretHash;
        final boolean active;
        final boolean canManageAccounts;
        final long expiresAt;
        CachedApp(UUID appId, String secretHash, boolean active, boolean canManageAccounts) {
            this.appId = appId;
            this.secretHash = secretHash;
            this.active = active;
            this.canManageAccounts = canManageAccounts;
            this.expiresAt = System.currentTimeMillis() + TENANT_CACHE_TTL_MS;
        }
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }

    private static final ConcurrentHashMap<String, CachedApp> tenantCache = new ConcurrentHashMap<>();

    /** Immediately evicts a rotated/revoked/deactivated App's cache entry, keyed by its (now stale) api_key. */
    public static void evictTenantCache(String apiKey) {
        if (apiKey != null) tenantCache.remove(apiKey);
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
     * Resolves the X-API-Key / X-API-Secret header pair to an active app:
     * X-API-Key is looked up directly (it's a non-secret identifier, not
     * hashed), then the SHA-256 hash of X-API-Secret is compared against the
     * stored hash. On success sets APP_ID_ATTR/CAN_MANAGE_ACCOUNTS_ATTR and
     * returns true.
     */
    public static boolean resolveTenantApp(HttpServletRequest req) {
        String apiKey = req.getHeader(API_KEY_HEADER);
        String apiSecret = req.getHeader(API_SECRET_HEADER);
        if (apiKey == null || apiKey.trim().isEmpty() || apiSecret == null || apiSecret.trim().isEmpty()) return false;

        CachedApp cached = tenantCache.get(apiKey);
        if (cached != null && !cached.isExpired()) {
            if (!cached.active || !cached.secretHash.equals(sha256Hex(apiSecret))) return false;
            req.setAttribute(APP_ID_ATTR, cached.appId);
            req.setAttribute(CAN_MANAGE_ACCOUNTS_ATTR, cached.canManageAccounts);
            return true;
        }
        tenantCache.remove(apiKey);

        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                    "SELECT id, is_active, can_manage_accounts, api_secret_hash FROM apps WHERE api_key = ?");
            pstmt.setString(1, apiKey);
            rs = pstmt.executeQuery();
            if (rs.next()) {
                UUID appId = UUID.fromString(rs.getString("id"));
                boolean active = rs.getBoolean("is_active");
                boolean canManageAccounts = rs.getBoolean("can_manage_accounts");
                String secretHash = rs.getString("api_secret_hash");
                tenantCache.put(apiKey, new CachedApp(appId, secretHash, active, canManageAccounts));
                if (!active || secretHash == null || !secretHash.equals(sha256Hex(apiSecret))) return false;
                req.setAttribute(APP_ID_ATTR, appId);
                req.setAttribute(CAN_MANAGE_ACCOUNTS_ATTR, canManageAccounts);
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

    /** True for a console Admin, or a tenant App authenticated with can_manage_accounts=true. */
    public static boolean canManageAccounts(HttpServletRequest req) {
        if (isAdmin(req)) return true;
        return Boolean.TRUE.equals(req.getAttribute(CAN_MANAGE_ACCOUNTS_ATTR));
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
