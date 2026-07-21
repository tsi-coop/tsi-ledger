package org.tsicoop.ledger.service.v1;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.tsicoop.ledger.framework.Action;
import org.tsicoop.ledger.framework.OutputProcessor;

/**
 * Admin-only tenant provisioning: create_app, list_apps, get_app,
 * rotate_api_key, revoke_api_key, deactivate_app. Implemented in Step 3.
 */
public class App implements Action {

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        OutputProcessor.errorResponse(res, 501, "Not Implemented", "App provisioning lands in Step 3.", req.getRequestURI());
    }

    @Override
    public boolean validate(String method, HttpServletRequest req, HttpServletResponse res) {
        return true;
    }
}
