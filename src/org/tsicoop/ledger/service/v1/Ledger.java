package org.tsicoop.ledger.service.v1;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.tsicoop.ledger.framework.Action;
import org.tsicoop.ledger.framework.OutputProcessor;

/**
 * Core ledger engine: post_entry — balance validation, idempotency,
 * hash-chain computation, ACID insert of journal_entries + entry_lines.
 * Implemented in Step 5.
 */
public class Ledger implements Action {

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        OutputProcessor.errorResponse(res, 501, "Not Implemented", "Core ledger engine lands in Step 5.", req.getRequestURI());
    }

    @Override
    public boolean validate(String method, HttpServletRequest req, HttpServletResponse res) {
        return true;
    }
}
