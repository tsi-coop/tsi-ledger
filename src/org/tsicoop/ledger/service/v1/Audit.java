package org.tsicoop.ledger.service.v1;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.tsicoop.ledger.framework.Action;
import org.tsicoop.ledger.framework.OutputProcessor;

/**
 * Hash-chain audit verification: verify_audit — walks journal_entries in
 * sequence order, recomputes each block hash, reports the first breach index
 * on mismatch. Implemented in Step 6. (Not the DPDP CMS audit-log service —
 * this one verifies journal_entries.block_hash, not an admin action log.)
 */
public class Audit implements Action {

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        OutputProcessor.errorResponse(res, 501, "Not Implemented", "Audit chain verification lands in Step 6.", req.getRequestURI());
    }

    @Override
    public boolean validate(String method, HttpServletRequest req, HttpServletResponse res) {
        return true;
    }
}
