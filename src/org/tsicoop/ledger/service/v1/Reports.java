package org.tsicoop.ledger.service.v1;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.tsicoop.ledger.framework.Action;
import org.tsicoop.ledger.framework.OutputProcessor;

/**
 * Financial reporting: trial_balance, balance_sheet. Implemented in Step 7.
 */
public class Reports implements Action {

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        OutputProcessor.errorResponse(res, 501, "Not Implemented", "Financial reporting lands in Step 7.", req.getRequestURI());
    }

    @Override
    public boolean validate(String method, HttpServletRequest req, HttpServletResponse res) {
        return true;
    }
}
