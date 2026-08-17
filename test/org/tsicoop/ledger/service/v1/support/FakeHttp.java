package org.tsicoop.ledger.service.v1.support;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.tsicoop.ledger.framework.JacksonUtil;
import org.json.simple.JSONObject;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/**
 * Minimal HttpServletRequest/Response doubles for driving an Action's
 * post(req, res) directly in a test, without a servlet container. Built as
 * java.lang.reflect.Proxy handlers rather than hand-implementing every
 * method on the (large, jakarta.servlet 6.0) interfaces — only the handful
 * of methods Ledger/InputProcessor/OutputProcessor actually call are given
 * real behavior; everything else returns a type-appropriate default, which
 * is safe because production code under test never calls them.
 */
public final class FakeHttp {

    private FakeHttp() {}

    public static HttpServletRequest request(Map<String, Object> attributes, Map<String, String> headers) {
        return (HttpServletRequest) Proxy.newProxyInstance(
                FakeHttp.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                new RequestHandler(attributes, headers));
    }

    /** Convenience: a request carrying a parsed JSON body (InputProcessor.getInput reads this
     * attribute directly), an Idempotency-Key header, and a tenant app_id request attribute —
     * the shape Ledger.post_entry/reverse_entry expect from an App's own API-key call. */
    public static HttpServletRequest tenantRequest(String bodyJson, java.util.UUID appId, String idempotencyKey) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(org.tsicoop.ledger.framework.InputProcessor.REQUEST_DATA, bodyJson);
        if (appId != null) attrs.put(org.tsicoop.ledger.framework.InputProcessor.APP_ID_ATTR, appId);
        Map<String, String> headers = new HashMap<>();
        if (idempotencyKey != null) headers.put("Idempotency-Key", idempotencyKey);
        return request(attrs, headers);
    }

    /** Convenience: a request carrying a parsed JSON body plus a console-session user_id/role —
     * the shape Ledger's console maker-checker functions (request_reversal/approve_reversal) expect. */
    public static HttpServletRequest consoleRequest(String bodyJson, java.util.UUID userId, String role) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(org.tsicoop.ledger.framework.InputProcessor.REQUEST_DATA, bodyJson);
        attrs.put(org.tsicoop.ledger.framework.InputProcessor.USER_ID_ATTR, userId);
        attrs.put(org.tsicoop.ledger.framework.InputProcessor.USER_ROLE_ATTR, role);
        return request(attrs, new HashMap<>());
    }

    public static CapturedResponse response() {
        return new CapturedResponse();
    }

    public static final class CapturedResponse {
        private volatile int status = 200;
        private final Map<String, String> headers = new HashMap<>();
        private final StringWriter body = new StringWriter();
        private final PrintWriter writer = new PrintWriter(body);

        public HttpServletResponse asServletResponse() {
            return (HttpServletResponse) Proxy.newProxyInstance(
                    FakeHttp.class.getClassLoader(),
                    new Class<?>[]{HttpServletResponse.class},
                    new ResponseHandler(this));
        }

        public int status() { return status; }

        public String bodyText() { writer.flush(); return body.toString(); }

        public JSONObject bodyJson() {
            try {
                return JacksonUtil.parse(bodyText());
            } catch (Exception e) {
                throw new RuntimeException("Response body was not valid JSON: " + bodyText(), e);
            }
        }
    }

    private static class RequestHandler implements InvocationHandler {
        private final Map<String, Object> attributes;
        private final Map<String, String> headers;

        RequestHandler(Map<String, Object> attributes, Map<String, String> headers) {
            this.attributes = attributes;
            this.headers = headers;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "getAttribute": return attributes.get(args[0]);
                case "setAttribute": attributes.put((String) args[0], args[1]); return null;
                case "getHeader": return headers.get(args[0]);
                case "getRequestURI": return "/api/v1/test";
                case "getCookies": return null;
                case "equals": return proxy == args[0];
                case "hashCode": return System.identityHashCode(proxy);
                case "toString": return "FakeHttpServletRequest";
                default: return defaultReturn(method.getReturnType());
            }
        }
    }

    private static class ResponseHandler implements InvocationHandler {
        private final CapturedResponse captured;

        ResponseHandler(CapturedResponse captured) {
            this.captured = captured;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "setStatus": captured.status = (Integer) args[0]; return null;
                case "getStatus": return captured.status;
                case "setHeader": captured.headers.put((String) args[0], (String) args[1]); return null;
                case "getHeader": return captured.headers.get(args[0]);
                case "getWriter": return captured.writer;
                case "setContentType": case "setCharacterEncoding": return null;
                case "equals": return proxy == args[0];
                case "hashCode": return System.identityHashCode(proxy);
                case "toString": return "FakeHttpServletResponse";
                default: return defaultReturn(method.getReturnType());
            }
        }
    }

    private static Object defaultReturn(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == void.class) return null;
        if (type == boolean.class) return Boolean.FALSE;
        if (type == long.class) return Long.valueOf(0L);
        if (type == short.class) return Short.valueOf((short) 0);
        if (type == byte.class) return Byte.valueOf((byte) 0);
        if (type == char.class) return Character.valueOf((char) 0);
        if (type == float.class) return Float.valueOf(0f);
        if (type == double.class) return Double.valueOf(0d);
        return Integer.valueOf(0);
    }
}
