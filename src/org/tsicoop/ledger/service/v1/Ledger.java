package org.tsicoop.ledger.service.v1;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.tsicoop.ledger.framework.Action;
import org.tsicoop.ledger.framework.InputProcessor;
import org.tsicoop.ledger.framework.OutputProcessor;
import org.tsicoop.ledger.framework.PoolDB;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Core ledger engine (TENANT_OR_CONSOLE auth mode): post_entry, reverse_entry
 * (tenant, immediate) and request_reversal/approve_reversal/reject_reversal
 * (console, maker-checker) all share one hash-chain-continuation path per
 * app_id — a reversal is just the next ordinary entry in that app's chain,
 * entry_type='REVERSAL' and reverses_entry_id set, never a mutation of the
 * original row (FSD Section 4/5's "Strict Validation Workflow" / immutability
 * still holds).
 *
 * Reversal channel split (a deliberate design choice, not a spec mandate):
 * an App reversing its OWN entry via reverse_entry (X-API-Key) posts
 * immediately, same trust level post_entry already has for that app.
 * Console-initiated reversals (request_reversal) go through maker-checker —
 * a different Admin must approve_reversal before the offsetting entry posts —
 * because there's no other system-of-record validating a manual correction.
 */
public class Ledger implements Action {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        try {
            JSONObject input = InputProcessor.getInput(req);
            String func = (String) input.get("_func");

            switch (func.toLowerCase()) {
                case "post_entry": handlePostEntry(input, res, req); break;
                case "list_entries": handleListEntries(input, res, req); break;
                case "get_entry": handleGetEntry(input, res, req); break;
                case "reverse_entry": handleReverseEntry(input, res, req); break;
                case "request_reversal": handleRequestReversal(input, res, req); break;
                case "approve_reversal": handleApproveReversal(input, res, req); break;
                case "reject_reversal": handleRejectReversal(input, res, req); break;
                case "list_reversal_requests": handleListReversalRequests(input, res, req); break;
                default:
                    OutputProcessor.errorResponse(res, 400, "Bad Request", "Unknown function: " + func, req.getRequestURI());
            }
        } catch (Exception e) {
            OutputProcessor.errorResponse(res, 500, "Internal Error", e.getMessage(), req.getRequestURI());
        }
    }

    @Override
    public boolean validate(String method, HttpServletRequest req, HttpServletResponse res) {
        return true;
    }

    // ---------- post_entry ----------

    private void handlePostEntry(JSONObject input, HttpServletResponse res, HttpServletRequest req) throws Exception {
        UUID appId = InputProcessor.getAppId(req);
        UUID postedByUserId = null;
        if (appId == null) {
            // Console-originated manual entry: caller identifies which App's
            // ledger/sequence this belongs to explicitly (tenant calls get it
            // from their resolved API key instead).
            appId = parseAppId(input.get("app_id"), res, req);
            if (appId == null) return;
            postedByUserId = InputProcessor.getUserId(req);
        }

        String idempotencyKey = req.getHeader(IDEMPOTENCY_KEY_HEADER);
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty() || idempotencyKey.length() > 255) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "Missing or invalid Idempotency-Key header.", req.getRequestURI());
            return;
        }

        String referenceId = (String) input.get("reference_id");
        String description = (String) input.get("description");
        Instant postedAt = parseInstant((String) input.get("posted_at"));
        if (postedAt == null) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "posted_at must be an ISO-8601 timestamp.", req.getRequestURI());
            return;
        }
        // Truncated to microseconds: Postgres TIMESTAMPTZ only stores microsecond precision, so a
        // caller-supplied posted_at with finer (e.g. nanosecond) precision would otherwise get
        // silently rounded on insert — the hash computed here, from the pre-insert value, would
        // then never match what verify_audit recomputes from the post-insert (truncated) stored
        // value. Same fix as postReversalEntry's Instant.now() truncation, below.
        postedAt = postedAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);

        List<HashChain.Line> lines = new ArrayList<>();
        LinkedHashSet<String> requestedCodes = new LinkedHashSet<>();
        BigDecimal sumDebit = BigDecimal.ZERO;
        BigDecimal sumCredit = BigDecimal.ZERO;

        for (Object o : (JSONArray) input.get("lines")) {
            JSONObject line = (JSONObject) o;
            String accountCode = (String) line.get("account_code");
            String type = normalizeLineType((String) line.get("type"));
            BigDecimal amount = toAmount(line.get("amount"));

            if (type == null || amount == null) {
                OutputProcessor.errorResponse(res, 400, "Bad Request", "Each line requires account_code, type (DEBIT/CREDIT), and a numeric amount.", req.getRequestURI());
                return;
            }
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                OutputProcessor.errorResponse(res, 400, "Bad Request", "Line amount must be greater than zero: " + accountCode, req.getRequestURI());
                return;
            }
            if (amount.scale() > 4) {
                OutputProcessor.errorResponse(res, 400, "Bad Request", "Line amount for " + accountCode + " has more than 4 decimal places.", req.getRequestURI());
                return;
            }

            lines.add(new HashChain.Line(accountCode, type, amount));
            requestedCodes.add(accountCode);
            if ("DEBIT".equals(type)) sumDebit = sumDebit.add(amount); else sumCredit = sumCredit.add(amount);
        }

        PoolDB pool = null;
        Connection conn = null;
        try {
            pool = new PoolDB(false);
            conn = pool.getConnection();

            Map<String, UUID> accountIds = resolveAccounts(conn, requestedCodes, res, req);
            if (accountIds == null) { conn.rollback(); return; }

            if (sumDebit.compareTo(sumCredit) != 0) {
                conn.rollback();
                OutputProcessor.errorResponse(res, 400, "Bad Request",
                        "Entry does not balance: debits=" + sumDebit.toPlainString() + " credits=" + sumCredit.toPlainString(),
                        req.getRequestURI());
                return;
            }

            lockAppRow(conn, appId);

            JSONObject existing = findByIdempotencyKey(conn, appId, idempotencyKey);
            if (existing != null) {
                conn.commit();
                existing.put("idempotent_replay", true);
                OutputProcessor.send(res, 200, existing);
                return;
            }

            ChainPosition pos = fetchChainPosition(conn, appId);
            String canonicalPayload = HashChain.canonicalPayload(referenceId, description, postedAt, lines);
            String payloadHash = HashChain.entryPayloadHash(canonicalPayload);
            String blockHash = HashChain.blockHash(payloadHash, pos.previousBlockHash);

            InsertedEntry inserted = insertEntry(conn, appId, pos.nextSequence, referenceId, description, postedAt,
                    idempotencyKey, payloadHash, pos.previousBlockHash, blockHash, "STANDARD", null, postedByUserId);
            insertLines(conn, inserted.id, lines, accountIds);

            conn.commit();

            JSONObject output = new JSONObject();
            output.put("id", inserted.id.toString());
            output.put("app_id", appId.toString());
            output.put("sequence_number", pos.nextSequence);
            output.put("reference_id", referenceId);
            output.put("description", description);
            output.put("posted_at", postedAt.toString());
            output.put("idempotency_key", idempotencyKey);
            output.put("entry_payload_hash", payloadHash);
            output.put("previous_block_hash", pos.previousBlockHash);
            output.put("block_hash", blockHash);
            output.put("entry_type", "STANDARD");
            output.put("created_at", inserted.createdAt.toInstant().toString());
            output.put("lines", linesToJson(lines));
            output.put("idempotent_replay", false);
            OutputProcessor.send(res, 201, output);
        } catch (Exception e) {
            if (conn != null) { try { conn.rollback(); } catch (SQLException ignored) {} }
            throw e;
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    // ---------- list_entries / get_entry ----------

    /** Tenant call: own app only (as v1). Console call: org-wide, optional app_id/date_from/date_to filters. */
    private void handleListEntries(JSONObject input, HttpServletResponse res, HttpServletRequest req) throws Exception {
        UUID tenantAppId = InputProcessor.getAppId(req);
        long limit = clamp(toLong(input.get("limit"), 50L), 1L, 200L);
        long offset = Math.max(0L, toLong(input.get("offset"), 0L));

        UUID filterAppId = tenantAppId;
        Instant dateFrom = null;
        Instant dateTo = null;
        if (tenantAppId == null) {
            if (input.get("app_id") != null) {
                filterAppId = parseAppId(input.get("app_id"), res, req);
                if (filterAppId == null) return;
            }
            dateFrom = parseInstant((String) input.get("date_from"));
            dateTo = parseInstant((String) input.get("date_to"));
        }

        StringBuilder sql = new StringBuilder(
                "SELECT je.id, je.app_id, a.app_name, je.sequence_number, je.reference_id, je.description, je.posted_at, " +
                "je.entry_type, je.reverses_entry_id, je.block_hash, je.created_at " +
                "FROM journal_entries je JOIN apps a ON a.id = je.app_id WHERE 1=1");
        if (filterAppId != null) sql.append(" AND je.app_id = ?");
        if (dateFrom != null) sql.append(" AND je.posted_at >= ?");
        if (dateTo != null) sql.append(" AND je.posted_at <= ?");
        sql.append(" ORDER BY je.app_id, je.sequence_number DESC LIMIT ? OFFSET ?");

        PoolDB pool = null; Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            JSONArray entries = new JSONArray();
            try (PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
                int i = 1;
                if (filterAppId != null) pstmt.setObject(i++, filterAppId);
                if (dateFrom != null) pstmt.setTimestamp(i++, Timestamp.from(dateFrom));
                if (dateTo != null) pstmt.setTimestamp(i++, Timestamp.from(dateTo));
                pstmt.setLong(i++, limit);
                pstmt.setLong(i, offset);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        JSONObject row = new JSONObject();
                        row.put("id", rs.getString("id"));
                        row.put("app_id", rs.getString("app_id"));
                        row.put("app_name", rs.getString("app_name"));
                        row.put("sequence_number", rs.getLong("sequence_number"));
                        row.put("reference_id", rs.getString("reference_id"));
                        row.put("description", rs.getString("description"));
                        row.put("posted_at", rs.getTimestamp("posted_at").toInstant().toString());
                        row.put("entry_type", rs.getString("entry_type"));
                        row.put("reverses_entry_id", rs.getString("reverses_entry_id"));
                        row.put("block_hash", rs.getString("block_hash"));
                        row.put("created_at", rs.getTimestamp("created_at").toInstant().toString());
                        entries.add(row);
                    }
                }
            }

            JSONObject output = new JSONObject();
            output.put("entries", entries);
            output.put("limit", limit);
            output.put("offset", offset);
            output.put("count", entries.size());
            OutputProcessor.send(res, 200, output);
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    private void handleGetEntry(JSONObject input, HttpServletResponse res, HttpServletRequest req) throws SQLException {
        UUID tenantAppId = InputProcessor.getAppId(req);
        UUID entryId;
        try {
            entryId = UUID.fromString((String) input.get("entry_id"));
        } catch (IllegalArgumentException e) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "entry_id must be a UUID.", req.getRequestURI());
            return;
        }

        StringBuilder sql = new StringBuilder(
                "SELECT je.id, je.app_id, a.app_name, je.sequence_number, je.reference_id, je.description, je.posted_at, " +
                "je.idempotency_key, je.entry_payload_hash, je.previous_block_hash, je.block_hash, je.entry_type, " +
                "je.reverses_entry_id, je.created_at FROM journal_entries je JOIN apps a ON a.id = je.app_id WHERE je.id = ?");
        if (tenantAppId != null) sql.append(" AND je.app_id = ?");

        PoolDB pool = null; Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
                pstmt.setObject(1, entryId);
                if (tenantAppId != null) pstmt.setObject(2, tenantAppId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (!rs.next()) {
                        OutputProcessor.errorResponse(res, 404, "Not Found", "Entry not found.", req.getRequestURI());
                        return;
                    }
                    JSONObject output = new JSONObject();
                    output.put("id", rs.getString("id"));
                    output.put("app_id", rs.getString("app_id"));
                    output.put("app_name", rs.getString("app_name"));
                    output.put("sequence_number", rs.getLong("sequence_number"));
                    output.put("reference_id", rs.getString("reference_id"));
                    output.put("description", rs.getString("description"));
                    output.put("posted_at", rs.getTimestamp("posted_at").toInstant().toString());
                    output.put("idempotency_key", rs.getString("idempotency_key"));
                    output.put("entry_payload_hash", rs.getString("entry_payload_hash"));
                    output.put("previous_block_hash", rs.getString("previous_block_hash"));
                    output.put("block_hash", rs.getString("block_hash"));
                    output.put("entry_type", rs.getString("entry_type"));
                    output.put("reverses_entry_id", rs.getString("reverses_entry_id"));
                    output.put("created_at", rs.getTimestamp("created_at").toInstant().toString());
                    output.put("lines", fetchLinesJson(conn, entryId));
                    OutputProcessor.send(res, 200, output);
                }
            }
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    // ---------- reverse_entry (tenant, immediate) ----------

    private void handleReverseEntry(JSONObject input, HttpServletResponse res, HttpServletRequest req) throws Exception {
        UUID appId = InputProcessor.getAppId(req);
        if (appId == null) {
            OutputProcessor.errorResponse(res, 403, "Forbidden",
                    "reverse_entry requires an App API key; console users should use request_reversal instead.", req.getRequestURI());
            return;
        }
        UUID originalEntryId;
        try {
            originalEntryId = UUID.fromString((String) input.get("entry_id"));
        } catch (Exception e) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "entry_id must be a UUID.", req.getRequestURI());
            return;
        }

        String idempotencyKey = req.getHeader(IDEMPOTENCY_KEY_HEADER);
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty() || idempotencyKey.length() > 255) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "Missing or invalid Idempotency-Key header.", req.getRequestURI());
            return;
        }

        PoolDB pool = null; Connection conn = null;
        try {
            pool = new PoolDB(false);
            conn = pool.getConnection();

            JSONObject existing = findByIdempotencyKey(conn, appId, idempotencyKey);
            if (existing != null) {
                conn.commit();
                existing.put("idempotent_replay", true);
                OutputProcessor.send(res, 200, existing);
                return;
            }

            OriginalEntry original = fetchOriginalEntry(conn, originalEntryId);
            if (original == null || !original.appId.equals(appId)) {
                conn.rollback();
                OutputProcessor.errorResponse(res, 404, "Not Found", "Entry not found for this app.", req.getRequestURI());
                return;
            }
            // Lock before the reversible check (not just before insertion, inside
            // postReversalEntry) so a second concurrent reverse_entry/approve_reversal
            // call for the same original entry blocks here and re-observes the first
            // one's committed reversal once it proceeds — closes the TOCTOU that a
            // lock taken only immediately before the insert would leave open.
            lockAppRow(conn, original.appId);
            String blocked = validateReversible(conn, original, null);
            if (blocked != null) {
                conn.rollback();
                OutputProcessor.errorResponse(res, 409, "Conflict", blocked, req.getRequestURI());
                return;
            }

            InsertedEntry inserted = postReversalEntry(conn, original, idempotencyKey, null, null);
            conn.commit();
            OutputProcessor.send(res, 201, reversalEntryJson(inserted, appId, original.id, idempotencyKey));
        } catch (Exception e) {
            if (conn != null) { try { conn.rollback(); } catch (SQLException ignored) {} }
            throw e;
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    // ---------- console maker-checker reversal workflow ----------

    private void handleRequestReversal(JSONObject input, HttpServletResponse res, HttpServletRequest req) throws SQLException {
        UUID userId = InputProcessor.getUserId(req);
        if (userId == null) {
            OutputProcessor.errorResponse(res, 403, "Forbidden", "Console login required.", req.getRequestURI());
            return;
        }
        UUID originalEntryId;
        try {
            originalEntryId = UUID.fromString((String) input.get("entry_id"));
        } catch (Exception e) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "entry_id must be a UUID.", req.getRequestURI());
            return;
        }
        String reason = (String) input.get("reason");
        if (reason == null || reason.trim().isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "reason is required.", req.getRequestURI());
            return;
        }

        PoolDB pool = null; Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            OriginalEntry original = fetchOriginalEntry(conn, originalEntryId);
            if (original == null) {
                OutputProcessor.errorResponse(res, 404, "Not Found", "Entry not found.", req.getRequestURI());
                return;
            }
            String blocked = validateReversible(conn, original, null);
            if (blocked != null) {
                OutputProcessor.errorResponse(res, 409, "Conflict", blocked, req.getRequestURI());
                return;
            }

            try (PreparedStatement pstmt = conn.prepareStatement(
                    "INSERT INTO reversal_requests (original_entry_id, requested_by_user_id, reason) VALUES (?, ?, ?) " +
                    "RETURNING id, status, created_at")) {
                pstmt.setObject(1, originalEntryId);
                pstmt.setObject(2, userId);
                pstmt.setString(3, reason.trim());
                try (ResultSet rs = pstmt.executeQuery()) {
                    rs.next();
                    JSONObject output = new JSONObject();
                    output.put("id", rs.getString("id"));
                    output.put("original_entry_id", originalEntryId.toString());
                    output.put("status", rs.getString("status"));
                    output.put("created_at", rs.getTimestamp("created_at").toInstant().toString());
                    OutputProcessor.send(res, 201, output);
                }
            } catch (SQLException e) {
                if ("23505".equals(e.getSQLState())) {
                    OutputProcessor.errorResponse(res, 409, "Conflict", "This entry already has a pending or completed reversal.", req.getRequestURI());
                } else {
                    throw e;
                }
            }
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    private void handleApproveReversal(JSONObject input, HttpServletResponse res, HttpServletRequest req) throws Exception {
        UUID approverId = InputProcessor.getUserId(req);
        if (!InputProcessor.isAdmin(req)) {
            OutputProcessor.errorResponse(res, 403, "Forbidden", "Admin role required to approve reversals.", req.getRequestURI());
            return;
        }
        UUID requestId;
        try {
            requestId = UUID.fromString((String) input.get("reversal_request_id"));
        } catch (Exception e) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "reversal_request_id must be a UUID.", req.getRequestURI());
            return;
        }

        PoolDB pool = null; Connection conn = null;
        try {
            pool = new PoolDB(false);
            conn = pool.getConnection();

            ReversalRequest reqRow = fetchReversalRequest(conn, requestId);
            if (reqRow == null || !"PENDING".equals(reqRow.status)) {
                conn.rollback();
                OutputProcessor.errorResponse(res, 409, "Conflict", "Reversal request is not pending.", req.getRequestURI());
                return;
            }
            if (reqRow.requestedByUserId.equals(approverId)) {
                conn.rollback();
                OutputProcessor.errorResponse(res, 403, "Forbidden", "A different user must approve this reversal (maker-checker).", req.getRequestURI());
                return;
            }

            OriginalEntry original = fetchOriginalEntry(conn, reqRow.originalEntryId);
            if (original != null) lockAppRow(conn, original.appId);
            // Exclude reqRow's own id: it's still PENDING at this point in the transaction
            // (only flips to APPROVED below, after this check), so without excluding it
            // every approval would see its own request and block itself.
            String blocked = original == null ? "Original entry not found." : validateReversible(conn, original, requestId);
            if (blocked != null) {
                conn.rollback();
                OutputProcessor.errorResponse(res, 409, "Conflict", blocked, req.getRequestURI());
                return;
            }

            String idempotencyKey = "reversal-request:" + requestId;
            InsertedEntry inserted = postReversalEntry(conn, original, idempotencyKey, approverId, reqRow.reason);

            try (PreparedStatement pstmt = conn.prepareStatement(
                    "UPDATE reversal_requests SET status = 'APPROVED', approved_by_user_id = ?, resulting_entry_id = ?, decided_at = now() WHERE id = ?")) {
                pstmt.setObject(1, approverId);
                pstmt.setObject(2, inserted.id);
                pstmt.setObject(3, requestId);
                pstmt.executeUpdate();
            }

            conn.commit();
            JSONObject output = reversalEntryJson(inserted, original.appId, original.id, idempotencyKey);
            output.put("reversal_request_id", requestId.toString());
            OutputProcessor.send(res, 200, output);
        } catch (Exception e) {
            if (conn != null) { try { conn.rollback(); } catch (SQLException ignored) {} }
            throw e;
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    private void handleRejectReversal(JSONObject input, HttpServletResponse res, HttpServletRequest req) throws SQLException {
        if (!InputProcessor.isAdmin(req)) {
            OutputProcessor.errorResponse(res, 403, "Forbidden", "Admin role required to reject reversals.", req.getRequestURI());
            return;
        }
        UUID requestId;
        try {
            requestId = UUID.fromString((String) input.get("reversal_request_id"));
        } catch (Exception e) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "reversal_request_id must be a UUID.", req.getRequestURI());
            return;
        }
        String decisionReason = (String) input.get("decision_reason");
        UUID approverId = InputProcessor.getUserId(req);

        PoolDB pool = null; Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "UPDATE reversal_requests SET status = 'REJECTED', approved_by_user_id = ?, decision_reason = ?, decided_at = now() " +
                    "WHERE id = ? AND status = 'PENDING' RETURNING id")) {
                pstmt.setObject(1, approverId);
                pstmt.setString(2, decisionReason);
                pstmt.setObject(3, requestId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (!rs.next()) {
                        OutputProcessor.errorResponse(res, 409, "Conflict", "Reversal request is not pending.", req.getRequestURI());
                        return;
                    }
                }
            }
            JSONObject output = new JSONObject();
            output.put("id", requestId.toString());
            output.put("status", "REJECTED");
            OutputProcessor.send(res, 200, output);
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    private void handleListReversalRequests(JSONObject input, HttpServletResponse res, HttpServletRequest req) throws SQLException {
        if (InputProcessor.getUserId(req) == null) {
            OutputProcessor.errorResponse(res, 403, "Forbidden", "Console login required.", req.getRequestURI());
            return;
        }
        String statusFilter = (String) input.get("status");

        StringBuilder sql = new StringBuilder(
                "SELECT rr.id, rr.original_entry_id, rr.requested_by_user_id, ru.name AS requested_by_name, " +
                "rr.reason, rr.status, rr.approved_by_user_id, au.name AS approved_by_name, rr.decision_reason, " +
                "rr.resulting_entry_id, rr.created_at, rr.decided_at, je.app_id, ap.app_name, je.sequence_number " +
                "FROM reversal_requests rr " +
                "JOIN journal_entries je ON je.id = rr.original_entry_id " +
                "JOIN apps ap ON ap.id = je.app_id " +
                "JOIN operators ru ON ru.id = rr.requested_by_user_id " +
                "LEFT JOIN operators au ON au.id = rr.approved_by_user_id WHERE 1=1");
        if (statusFilter != null) sql.append(" AND rr.status = ?");
        sql.append(" ORDER BY rr.created_at DESC");

        JSONArray output = new JSONArray();
        PoolDB pool = null; Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
                if (statusFilter != null) pstmt.setString(1, statusFilter.toUpperCase());
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        JSONObject row = new JSONObject();
                        row.put("id", rs.getString("id"));
                        row.put("original_entry_id", rs.getString("original_entry_id"));
                        row.put("app_id", rs.getString("app_id"));
                        row.put("app_name", rs.getString("app_name"));
                        row.put("sequence_number", rs.getLong("sequence_number"));
                        row.put("requested_by_user_id", rs.getString("requested_by_user_id"));
                        row.put("requested_by_name", rs.getString("requested_by_name"));
                        row.put("reason", rs.getString("reason"));
                        row.put("status", rs.getString("status"));
                        row.put("approved_by_user_id", rs.getString("approved_by_user_id"));
                        row.put("approved_by_name", rs.getString("approved_by_name"));
                        row.put("decision_reason", rs.getString("decision_reason"));
                        row.put("resulting_entry_id", rs.getString("resulting_entry_id"));
                        row.put("created_at", rs.getTimestamp("created_at").toInstant().toString());
                        row.put("decided_at", rs.getTimestamp("decided_at") != null ? rs.getTimestamp("decided_at").toInstant().toString() : null);
                        output.add(row);
                    }
                }
            }
            OutputProcessor.send(res, 200, output);
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    // ---------- shared reversal machinery ----------

    private static class OriginalEntry {
        UUID id; UUID appId; String entryType; String referenceId; String description;
    }

    private OriginalEntry fetchOriginalEntry(Connection conn, UUID entryId) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT id, app_id, entry_type, reference_id, description FROM journal_entries WHERE id = ?")) {
            pstmt.setObject(1, entryId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (!rs.next()) return null;
                OriginalEntry e = new OriginalEntry();
                e.id = UUID.fromString(rs.getString("id"));
                e.appId = UUID.fromString(rs.getString("app_id"));
                e.entryType = rs.getString("entry_type");
                e.referenceId = rs.getString("reference_id");
                e.description = rs.getString("description");
                return e;
            }
        }
    }

    /**
     * Returns a rejection message if the entry cannot be reversed, or null if it's clear to
     * reverse. excludeRequestId is non-null only when called from handleApproveReversal: that
     * request's own reversal_requests row is still PENDING at this point in its own transaction
     * (it only flips to APPROVED after this check passes), so without excluding it here every
     * approval would find its own pending row and block itself — approve_reversal could never
     * succeed for anyone. request_reversal/reverse_entry have no "own row" to exclude, so they
     * pass null and this behaves exactly as before for them.
     */
    private String validateReversible(Connection conn, OriginalEntry original, UUID excludeRequestId) throws SQLException {
        if ("REVERSAL".equals(original.entryType)) {
            return "A reversal entry cannot itself be reversed.";
        }
        String sql = "SELECT 1 FROM journal_entries WHERE reverses_entry_id = ? " +
                "UNION SELECT 1 FROM reversal_requests WHERE original_entry_id = ? AND status IN ('PENDING','APPROVED')" +
                (excludeRequestId != null ? " AND id != ?" : "") +
                " LIMIT 1";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setObject(1, original.id);
            pstmt.setObject(2, original.id);
            if (excludeRequestId != null) pstmt.setObject(3, excludeRequestId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) return "This entry has already been reversed or has a reversal in progress.";
            }
        }
        return null;
    }

    private static class ReversalRequest {
        UUID id; UUID originalEntryId; UUID requestedByUserId; String reason; String status;
    }

    private ReversalRequest fetchReversalRequest(Connection conn, UUID requestId) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT id, original_entry_id, requested_by_user_id, reason, status FROM reversal_requests WHERE id = ? FOR UPDATE")) {
            pstmt.setObject(1, requestId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (!rs.next()) return null;
                ReversalRequest r = new ReversalRequest();
                r.id = UUID.fromString(rs.getString("id"));
                r.originalEntryId = UUID.fromString(rs.getString("original_entry_id"));
                r.requestedByUserId = UUID.fromString(rs.getString("requested_by_user_id"));
                r.reason = rs.getString("reason");
                r.status = rs.getString("status");
                return r;
            }
        }
    }

    /** Builds mirrored (DEBIT<->CREDIT swapped) lines from the original entry and posts them as the
     * next hash-chained entry in the SAME app's sequence, entry_type=REVERSAL. Caller manages the
     * transaction (lock/commit/rollback) — this only inserts. */
    private InsertedEntry postReversalEntry(Connection conn, OriginalEntry original, String idempotencyKey,
                                             UUID postedByUserId, String reasonNote) throws SQLException {
        List<HashChain.Line> mirroredLines = fetchMirroredLines(conn, original.id);

        lockAppRow(conn, original.appId);
        ChainPosition pos = fetchChainPosition(conn, original.appId);

        String description = "Reversal of entry " + original.id + (reasonNote != null && !reasonNote.isBlank() ? ": " + reasonNote : "");
        // Truncated to microseconds: Postgres TIMESTAMPTZ only stores microsecond precision, so an
        // untruncated Instant.now() (which usually carries nanosecond precision) gets silently
        // rounded on insert — the hash computed here, from the pre-insert value, would then never
        // match what verify_audit recomputes from the post-insert (truncated) stored value.
        Instant postedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        String canonicalPayload = HashChain.canonicalPayload(original.referenceId, description, postedAt, mirroredLines);
        String payloadHash = HashChain.entryPayloadHash(canonicalPayload);
        String blockHash = HashChain.blockHash(payloadHash, pos.previousBlockHash);

        InsertedEntry inserted = insertEntry(conn, original.appId, pos.nextSequence, original.referenceId, description, postedAt,
                idempotencyKey, payloadHash, pos.previousBlockHash, blockHash, "REVERSAL", original.id, postedByUserId);

        Map<String, UUID> accountIds = new HashMap<>();
        for (HashChain.Line l : mirroredLines) accountIds.putIfAbsent(l.accountCode, resolveSingleAccount(conn, l.accountCode));
        insertLines(conn, inserted.id, mirroredLines, accountIds);

        inserted.sequenceNumber = pos.nextSequence;
        inserted.payloadHash = payloadHash;
        inserted.previousBlockHash = pos.previousBlockHash;
        inserted.blockHash = blockHash;
        inserted.postedAt = postedAt;
        inserted.lines = mirroredLines;
        return inserted;
    }

    private JSONObject reversalEntryJson(InsertedEntry inserted, UUID appId, UUID reversesEntryId, String idempotencyKey) {
        JSONObject output = new JSONObject();
        output.put("id", inserted.id.toString());
        output.put("app_id", appId.toString());
        output.put("sequence_number", inserted.sequenceNumber);
        output.put("reverses_entry_id", reversesEntryId.toString());
        output.put("entry_type", "REVERSAL");
        output.put("posted_at", inserted.postedAt.toString());
        output.put("idempotency_key", idempotencyKey);
        output.put("entry_payload_hash", inserted.payloadHash);
        output.put("previous_block_hash", inserted.previousBlockHash);
        output.put("block_hash", inserted.blockHash);
        output.put("created_at", inserted.createdAt.toInstant().toString());
        output.put("lines", linesToJson(inserted.lines));
        output.put("idempotent_replay", false);
        return output;
    }

    private List<HashChain.Line> fetchMirroredLines(Connection conn, UUID entryId) throws SQLException {
        List<HashChain.Line> mirrored = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT a.account_code, el.line_type, el.amount FROM entry_lines el " +
                "JOIN accounts a ON a.id = el.account_id WHERE el.entry_id = ?")) {
            pstmt.setObject(1, entryId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String swapped = "DEBIT".equals(rs.getString("line_type")) ? "CREDIT" : "DEBIT";
                    mirrored.add(new HashChain.Line(rs.getString("account_code"), swapped, rs.getBigDecimal("amount")));
                }
            }
        }
        return mirrored;
    }

    private UUID resolveSingleAccount(Connection conn, String accountCode) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement("SELECT id FROM accounts WHERE account_code = ?")) {
            pstmt.setString(1, accountCode);
            try (ResultSet rs = pstmt.executeQuery()) {
                rs.next();
                return UUID.fromString(rs.getString("id"));
            }
        }
    }

    // ---------- shared helpers ----------

    private static long toLong(Object raw, long fallback) {
        if (raw instanceof Long) return (Long) raw;
        if (raw instanceof Number) return ((Number) raw).longValue();
        return fallback;
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private UUID parseAppId(Object raw, HttpServletResponse res, HttpServletRequest req) {
        try {
            return UUID.fromString((String) raw);
        } catch (Exception e) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "app_id must be a UUID.", req.getRequestURI());
            return null;
        }
    }

    /** Returns null (and has already sent the error response) if any code is unknown or inactive. COA is global — no app_id predicate. */
    private Map<String, UUID> resolveAccounts(Connection conn, Set<String> codes,
                                               HttpServletResponse res, HttpServletRequest req) throws SQLException {
        Map<String, UUID> active = new HashMap<>();
        Set<String> inactive = new LinkedHashSet<>();
        Array codesArray = conn.createArrayOf("varchar", codes.toArray());
        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT account_code, id, is_active FROM accounts WHERE account_code = ANY(?)")) {
            pstmt.setArray(1, codesArray);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    if (rs.getBoolean("is_active")) {
                        active.put(rs.getString("account_code"), UUID.fromString(rs.getString("id")));
                    } else {
                        inactive.add(rs.getString("account_code"));
                    }
                }
            }
        }

        Set<String> unknown = new LinkedHashSet<>(codes);
        unknown.removeAll(active.keySet());
        unknown.removeAll(inactive);

        if (!unknown.isEmpty() || !inactive.isEmpty()) {
            StringBuilder msg = new StringBuilder("Invalid account_code(s).");
            if (!unknown.isEmpty()) msg.append(" Unknown: ").append(unknown).append(".");
            if (!inactive.isEmpty()) msg.append(" Inactive: ").append(inactive).append(".");
            OutputProcessor.errorResponse(res, 400, "Bad Request", msg.toString(), req.getRequestURI());
            return null;
        }
        return active;
    }

    private void lockAppRow(Connection conn, UUID appId) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement("SELECT id FROM apps WHERE id = ? FOR UPDATE")) {
            pstmt.setObject(1, appId);
            try (ResultSet rs = pstmt.executeQuery()) {
                rs.next();
            }
        }
    }

    private JSONObject findByIdempotencyKey(Connection conn, UUID appId, String idempotencyKey) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT id, sequence_number, reference_id, description, posted_at, entry_payload_hash, " +
                "previous_block_hash, block_hash, entry_type, reverses_entry_id, created_at FROM journal_entries WHERE app_id = ? AND idempotency_key = ?")) {
            pstmt.setObject(1, appId);
            pstmt.setString(2, idempotencyKey);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (!rs.next()) return null;
                UUID entryId = UUID.fromString(rs.getString("id"));
                JSONObject output = new JSONObject();
                output.put("id", entryId.toString());
                output.put("app_id", appId.toString());
                output.put("sequence_number", rs.getLong("sequence_number"));
                output.put("reference_id", rs.getString("reference_id"));
                output.put("description", rs.getString("description"));
                output.put("posted_at", rs.getTimestamp("posted_at").toInstant().toString());
                output.put("idempotency_key", idempotencyKey);
                output.put("entry_payload_hash", rs.getString("entry_payload_hash"));
                output.put("previous_block_hash", rs.getString("previous_block_hash"));
                output.put("block_hash", rs.getString("block_hash"));
                output.put("entry_type", rs.getString("entry_type"));
                output.put("reverses_entry_id", rs.getString("reverses_entry_id"));
                output.put("created_at", rs.getTimestamp("created_at").toInstant().toString());
                output.put("lines", fetchLinesJson(conn, entryId));
                return output;
            }
        }
    }

    private JSONArray fetchLinesJson(Connection conn, UUID entryId) throws SQLException {
        JSONArray lines = new JSONArray();
        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT a.account_code, el.line_type, el.amount FROM entry_lines el " +
                "JOIN accounts a ON a.id = el.account_id WHERE el.entry_id = ? ORDER BY a.account_code, el.line_type")) {
            pstmt.setObject(1, entryId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    JSONObject line = new JSONObject();
                    line.put("account_code", rs.getString("account_code"));
                    line.put("type", rs.getString("line_type"));
                    line.put("amount", rs.getBigDecimal("amount"));
                    lines.add(line);
                }
            }
        }
        return lines;
    }

    private static class ChainPosition {
        final long nextSequence;
        final String previousBlockHash;
        ChainPosition(long nextSequence, String previousBlockHash) {
            this.nextSequence = nextSequence;
            this.previousBlockHash = previousBlockHash;
        }
    }

    private ChainPosition fetchChainPosition(Connection conn, UUID appId) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT sequence_number, block_hash FROM journal_entries WHERE app_id = ? ORDER BY sequence_number DESC LIMIT 1")) {
            pstmt.setObject(1, appId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return new ChainPosition(rs.getLong("sequence_number") + 1, rs.getString("block_hash"));
                }
                return new ChainPosition(1L, HashChain.GENESIS_HASH);
            }
        }
    }

    private static class InsertedEntry {
        final UUID id;
        final Timestamp createdAt;
        long sequenceNumber;
        String payloadHash;
        String previousBlockHash;
        String blockHash;
        Instant postedAt;
        List<HashChain.Line> lines;
        InsertedEntry(UUID id, Timestamp createdAt) {
            this.id = id;
            this.createdAt = createdAt;
        }
    }

    private InsertedEntry insertEntry(Connection conn, UUID appId, long sequenceNumber, String referenceId, String description,
                                       Instant postedAt, String idempotencyKey, String payloadHash,
                                       String previousBlockHash, String blockHash, String entryType,
                                       UUID reversesEntryId, UUID postedByUserId) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "INSERT INTO journal_entries (app_id, sequence_number, reference_id, description, posted_at, " +
                "idempotency_key, entry_payload_hash, previous_block_hash, block_hash, entry_type, reverses_entry_id, posted_by_user_id) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id, created_at")) {
            pstmt.setObject(1, appId);
            pstmt.setLong(2, sequenceNumber);
            pstmt.setString(3, referenceId);
            pstmt.setString(4, description);
            pstmt.setTimestamp(5, Timestamp.from(postedAt));
            pstmt.setString(6, idempotencyKey);
            pstmt.setString(7, payloadHash);
            pstmt.setString(8, previousBlockHash);
            pstmt.setString(9, blockHash);
            pstmt.setString(10, entryType);
            pstmt.setObject(11, reversesEntryId);
            pstmt.setObject(12, postedByUserId);
            try (ResultSet rs = pstmt.executeQuery()) {
                rs.next();
                return new InsertedEntry(UUID.fromString(rs.getString("id")), rs.getTimestamp("created_at"));
            }
        }
    }

    private void insertLines(Connection conn, UUID entryId, List<HashChain.Line> lines, Map<String, UUID> accountIds) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "INSERT INTO entry_lines (entry_id, account_id, line_type, amount) VALUES (?,?,?,?)")) {
            for (HashChain.Line l : lines) {
                pstmt.setObject(1, entryId);
                pstmt.setObject(2, accountIds.get(l.accountCode));
                pstmt.setString(3, l.type);
                pstmt.setBigDecimal(4, l.amount.setScale(4));
                pstmt.addBatch();
            }
            pstmt.executeBatch();
        }
    }

    private JSONArray linesToJson(List<HashChain.Line> lines) {
        JSONArray arr = new JSONArray();
        for (HashChain.Line l : lines) {
            JSONObject line = new JSONObject();
            line.put("account_code", l.accountCode);
            line.put("type", l.type);
            line.put("amount", l.amount.setScale(4));
            arr.add(line);
        }
        return arr;
    }

    private static String normalizeLineType(String raw) {
        if (raw == null) return null;
        String upper = raw.toUpperCase();
        return ("DEBIT".equals(upper) || "CREDIT".equals(upper)) ? upper : null;
    }

    private static BigDecimal toAmount(Object raw) {
        try {
            if (raw instanceof BigDecimal) return (BigDecimal) raw;
            if (raw instanceof Number) return new BigDecimal(raw.toString());
            if (raw instanceof String) return new BigDecimal((String) raw);
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    private static Instant parseInstant(String raw) {
        if (raw == null) return null;
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e1) {
            try {
                return OffsetDateTime.parse(raw).toInstant();
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }
}
