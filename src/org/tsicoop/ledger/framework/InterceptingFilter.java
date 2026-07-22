package org.tsicoop.ledger.framework;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONObject;

import java.io.IOException;
import java.util.Properties;

/**
 * Routes every /api/v1/* call to the Action class registered for its path in
 * _processor.tsi, dispatching on the "_func" field in the JSON body (TSI's
 * func-dispatch convention — every call is POST, there is no per-verb routing).
 *
 * Auth is a two-way split, not DPDP CMS's admin/client/bootstrap/public
 * category system:
 *  - /api/v1/app (tenant provisioning) requires X-Admin-Key == ADMIN_MASTER_KEY.
 *  - Every other registered path is tenant-facing and requires a valid
 *    X-API-Key resolved to an active app (see InputProcessor.resolveTenantApp).
 */
public class InterceptingFilter implements Filter {

    private static final String API_PREFIX = "/api/v1/";
    private static final String ADMIN_PATH = "/api/v1/app";

    @Override
    public void destroy() {
    }

    private boolean isOriginAllowed(HttpServletRequest req) {
        String origin = req.getHeader("Origin");
        if (origin == null) return true;
        return matchAllowedOrigin(origin) != null;
    }

    /** ALLOWED_ORIGINS is a comma-separated allow-list; Access-Control-Allow-Origin must echo back
     * exactly the one matched origin (never the raw list — that's not a legal header value and
     * browsers will reject it, silently breaking CORS for every origin in the list). */
    private String matchAllowedOrigin(String origin) {
        String allowedOrigins = System.getenv("ALLOWED_ORIGINS");
        if (allowedOrigins == null || allowedOrigins.trim().isEmpty()) return null;
        for (String allowed : allowedOrigins.split(",")) {
            if (allowed.trim().equalsIgnoreCase(origin)) return allowed.trim();
        }
        return null;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        String method = req.getMethod();
        String uri = req.getRequestURI();

        String requestOrigin = req.getHeader("Origin");
        String matchedOrigin = requestOrigin != null ? matchAllowedOrigin(requestOrigin) : null;
        if (matchedOrigin != null) {
            res.setHeader("Access-Control-Allow-Origin", matchedOrigin);
            res.setHeader("Vary", "Origin");
        }
        res.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
        res.setHeader("Access-Control-Allow-Headers", "Origin, Content-Type, Accept, Authorization, X-API-Key, X-Admin-Key, Idempotency-Key");
        res.setHeader("Access-Control-Max-Age", "3600");

        res.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        res.setHeader("Content-Security-Policy", "default-src 'none'; script-src 'self'; connect-src 'self'; img-src 'self'; style-src 'self'");
        res.setHeader("X-Frame-Options", "DENY");
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("Permissions-Policy", "geolocation=(), microphone=(), camera=()");

        res.setCharacterEncoding("UTF-8");
        res.setContentType("application/json");

        if ("OPTIONS".equalsIgnoreCase(method)) {
            res.setStatus(HttpServletResponse.SC_OK);
            return;
        }

        if (!uri.startsWith(API_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        if (!"POST".equalsIgnoreCase(method)) {
            OutputProcessor.errorResponse(res, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "Method Not Allowed", "All API calls are POST with a _func body.", uri);
            return;
        }

        // Browser-originated calls (Ops Console) must come from an allowed origin.
        // Server-to-server tenant calls typically send no Origin header — not checked.
        if (ADMIN_PATH.equalsIgnoreCase(uri) && !isOriginAllowed(req)) {
            OutputProcessor.errorResponse(res, HttpServletResponse.SC_FORBIDDEN, "Forbidden", "Origin not permitted.", uri);
            return;
        }

        Properties apiRegistry = SystemConfig.getProcessorConfig();
        String servletPath = uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
        String classname = apiRegistry.getProperty(servletPath);
        if (classname == null) {
            OutputProcessor.errorResponse(res, HttpServletResponse.SC_NOT_FOUND, "Not Found", "API endpoint not found: " + uri, uri);
            return;
        }

        try {
            InputProcessor.processInput(req, res);
            JSONObject inputJson = InputProcessor.getInput(req);
            String func = (inputJson != null) ? (String) inputJson.get("_func") : null;

            if (inputJson == null) {
                OutputProcessor.errorResponse(res, HttpServletResponse.SC_BAD_REQUEST, "Bad Request", "Missing JSON request body.", uri);
                return;
            }
            if (func == null || func.trim().isEmpty()) {
                OutputProcessor.errorResponse(res, HttpServletResponse.SC_BAD_REQUEST, "Bad Request", "Missing required '_func' attribute in input JSON.", uri);
                return;
            }
            if (!InputProcessor.validate(req, res)) {
                return;
            }

            boolean authenticated;
            if (ADMIN_PATH.equalsIgnoreCase(servletPath)) {
                authenticated = InputProcessor.isValidAdminRequest(req) || InputProcessor.isValidAdminSession(req);
            } else {
                authenticated = InputProcessor.resolveTenantApp(req);
            }

            if (!authenticated) {
                OutputProcessor.errorResponse(res, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized", "Missing or invalid API credentials.", uri);
                return;
            }

            Action action = (Action) Class.forName(classname).getConstructor().newInstance();
            boolean validRequest = action.validate(method, req, res);
            if (validRequest) {
                action.post(req, res);
            }
        } catch (Exception e) {
            e.printStackTrace();
            OutputProcessor.errorResponse(res, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal Server Error", "An unexpected error occurred: " + e.getMessage(), uri);
        }
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        SystemConfig.loadProcessorConfig(filterConfig.getServletContext());
        System.out.println("Loaded _processor.tsi");
        SystemConfig.loadAppConfig(filterConfig.getServletContext());
        System.out.println("Loaded TSI Ledger App Config");
        JSONSchemaValidator.createInstance(filterConfig.getServletContext());
        System.out.println("Loaded TSI Ledger Schema Validator");
        System.out.println("TSI Ledger Service started in " + System.getenv("TSI_LEDGER_ENV") + " environment");
    }
}
