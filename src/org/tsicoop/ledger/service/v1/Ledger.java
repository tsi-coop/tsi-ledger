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
 * Core ledger engine: post_entry (FSD Section 4/5). The only Action in the
 * app that opens a manual (autocommit=false) transaction — every other write
 * path here is a single statement.
 *
 * Validation order follows the FSD's "Strict Validation Workflow": account
 * existence/active status, then balance, then idempotency. Account/balance
 * checks don't need the app row locked (they only read the reference chart
 * of accounts). The idempotency check and sequence/hash computation *do* run
 * after SELECT ... FOR UPDATE locks the parent apps row (db/01_init.sql's
 * concurrency note) — doing the idempotency re-check there too, rather than
 * only before the lock, closes a TOCTOU race: two concurrent retries of the
 * same Idempotency-Key would otherwise both pass an early "not found" check
 * and both attempt to insert.
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

    private void handlePostEntry(JSONObject input, HttpServletResponse res, HttpServletRequest req) throws Exception {
        UUID appId = InputProcessor.getAppId(req);

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

            Map<String, UUID> accountIds = resolveAccounts(conn, appId, requestedCodes, res, req);
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
                    idempotencyKey, payloadHash, pos.previousBlockHash, blockHash);
            insertLines(conn, inserted.id, lines, accountIds);

            conn.commit();

            JSONObject output = new JSONObject();
            output.put("id", inserted.id.toString());
            output.put("sequence_number", pos.nextSequence);
            output.put("reference_id", referenceId);
            output.put("description", description);
            output.put("posted_at", postedAt.toString());
            output.put("idempotency_key", idempotencyKey);
            output.put("entry_payload_hash", payloadHash);
            output.put("previous_block_hash", pos.previousBlockHash);
            output.put("block_hash", blockHash);
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

    /** Returns null (and has already sent the error response) if any code is unknown or inactive for this app. */
    private Map<String, UUID> resolveAccounts(Connection conn, UUID appId, Set<String> codes,
                                               HttpServletResponse res, HttpServletRequest req) throws SQLException {
        Map<String, UUID> active = new HashMap<>();
        Set<String> inactive = new LinkedHashSet<>();
        Array codesArray = conn.createArrayOf("varchar", codes.toArray());
        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT account_code, id, is_active FROM accounts WHERE app_id = ? AND account_code = ANY(?)")) {
            pstmt.setObject(1, appId);
            pstmt.setArray(2, codesArray);
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
            StringBuilder msg = new StringBuilder("Invalid account_code(s) for this app.");
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
                "previous_block_hash, block_hash, created_at FROM journal_entries WHERE app_id = ? AND idempotency_key = ?")) {
            pstmt.setObject(1, appId);
            pstmt.setString(2, idempotencyKey);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (!rs.next()) return null;
                UUID entryId = UUID.fromString(rs.getString("id"));
                JSONObject output = new JSONObject();
                output.put("id", entryId.toString());
                output.put("sequence_number", rs.getLong("sequence_number"));
                output.put("reference_id", rs.getString("reference_id"));
                output.put("description", rs.getString("description"));
                output.put("posted_at", rs.getTimestamp("posted_at").toInstant().toString());
                output.put("idempotency_key", idempotencyKey);
                output.put("entry_payload_hash", rs.getString("entry_payload_hash"));
                output.put("previous_block_hash", rs.getString("previous_block_hash"));
                output.put("block_hash", rs.getString("block_hash"));
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
        InsertedEntry(UUID id, Timestamp createdAt) {
            this.id = id;
            this.createdAt = createdAt;
        }
    }

    private InsertedEntry insertEntry(Connection conn, UUID appId, long sequenceNumber, String referenceId, String description,
                                       Instant postedAt, String idempotencyKey, String payloadHash,
                                       String previousBlockHash, String blockHash) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "INSERT INTO journal_entries (app_id, sequence_number, reference_id, description, posted_at, " +
                "idempotency_key, entry_payload_hash, previous_block_hash, block_hash) VALUES (?,?,?,?,?,?,?,?,?) " +
                "RETURNING id, created_at")) {
            pstmt.setObject(1, appId);
            pstmt.setLong(2, sequenceNumber);
            pstmt.setString(3, referenceId);
            pstmt.setString(4, description);
            pstmt.setTimestamp(5, Timestamp.from(postedAt));
            pstmt.setString(6, idempotencyKey);
            pstmt.setString(7, payloadHash);
            pstmt.setString(8, previousBlockHash);
            pstmt.setString(9, blockHash);
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
