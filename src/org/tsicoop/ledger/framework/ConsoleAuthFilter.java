package org.tsicoop.ledger.framework;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Server-side gate for the static Ops Console pages (mapped in web.xml). Without
 * it the pages are served to any HTTP client and the only login check is the
 * client-side JS in console.js. Requires a valid Ops Console session cookie
 * (issued by Auth.login), otherwise redirects to the login page.
 *
 * API calls are authenticated independently by InterceptingFilter.
 */
public class ConsoleAuthFilter implements Filter {

    @Override
    public void init(FilterConfig filterConfig) {
    }

    @Override
    public void destroy() {
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        if (!InputProcessor.resolveConsoleSession(req)) {
            String here = req.getRequestURI() + (req.getQueryString() != null ? "?" + req.getQueryString() : "");
            res.sendRedirect("/console/login.html?next=" + URLEncoder.encode(here, StandardCharsets.UTF_8));
            return;
        }

        chain.doFilter(request, response);
    }
}
