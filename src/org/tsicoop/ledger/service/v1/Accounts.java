package org.tsicoop.ledger.service.v1;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.tsicoop.ledger.framework.Action;
import org.tsicoop.ledger.framework.OutputProcessor;

/**
 * Tenant-facing chart of accounts: create_account, list_accounts, get_account.
 * Implemented in Step 4.
 */
public class Accounts implements Action {

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        OutputProcessor.errorResponse(res, 501, "Not Implemented", "Chart of accounts lands in Step 4.", req.getRequestURI());
    }

    @Override
    public boolean validate(String method, HttpServletRequest req, HttpServletResponse res) {
        return true;
    }
}
