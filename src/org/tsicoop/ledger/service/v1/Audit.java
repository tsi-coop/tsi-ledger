package org.tsicoop.ledger.service.v1;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.tsicoop.ledger.framework.Action;
import org.tsicoop.ledger.framework.InputProcessor;
import org.tsicoop.ledger.framework.OutputProcessor;
import org.tsicoop.ledger.framework.PoolDB;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Hash-chain audit verification: verify_audit (FSD Section 4.2/5, TDD Section
 * 5) — walks journal_entries for the tenant in sequence_number order,
 * recomputing P_n/H_n from the *currently stored* rows via HashChain (the
 * same algorithm post_entry used to write them) and comparing against the
 * stored entry_payload_hash/block_hash. Stops at the first breach — a
 * tamper-evident chain only tells you the earliest point it can still trust.
 *
 * Not the DPDP CMS Audit.java this repo's copied-from — that hash-chains
 * free-text admin actions into an audit_logs table. This verifies
 * journal_entries.block_hash, an unrelated concept.
 */
public class Audit implements Action {

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        try {
            JSONObject input = InputProcessor.getInput(req);
            String func = (String) input.get("_func");

            switch (func.toLowerCase()) {
                case "verify_audit": handleVerifyAudit(res, req); break;
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

    private static class EntryRow {
        UUID id;
        long sequenceNumber;
        String referenceId;
        String description;
        Instant postedAt;
        String entryPayloadHash;
        String previousBlockHash;
        String blockHash;
    }

    private void handleVerifyAudit(HttpServletResponse res, HttpServletRequest req) throws SQLException {
        UUID appId = InputProcessor.getAppId(req);

        PoolDB pool = null;
        Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            List<EntryRow> entries = fetchEntries(conn, appId);
            JSONObject output = new JSONObject();
            output.put("total_entries", entries.size());

            if (entries.isEmpty()) {
                output.put("valid", true);
                output.put("entries_verified", 0);
                output.put("last_block_hash", HashChain.GENESIS_HASH);
                OutputProcessor.send(res, 200, output);
                return;
            }

            Map<UUID, List<HashChain.Line>> linesByEntry = fetchLinesByEntry(conn, entries);

            long expectedSequence = 1L;
            String runningHash = HashChain.GENESIS_HASH;
            int verified = 0;

            for (EntryRow entry : entries) {
                if (entry.sequenceNumber != expectedSequence) {
                    output.put("valid", false);
                    output.put("entries_verified", verified);
                    output.put("breach", breach(expectedSequence, "sequence_gap",
                            "Expected sequence_number " + expectedSequence + " but found " + entry.sequenceNumber + " — an entry is missing or out of order.",
                            "expected_sequence_number", String.valueOf(expectedSequence),
                            "found_sequence_number", String.valueOf(entry.sequenceNumber)));
                    OutputProcessor.send(res, 200, output);
                    return;
                }

                if (!entry.previousBlockHash.equals(runningHash)) {
                    output.put("valid", false);
                    output.put("entries_verified", verified);
                    output.put("breach", breach(entry.sequenceNumber, "previous_hash_mismatch",
                            "Stored previous_block_hash does not match the verified hash of the prior block.",
                            "expected", runningHash, "stored", entry.previousBlockHash));
                    OutputProcessor.send(res, 200, output);
                    return;
                }

                List<HashChain.Line> lines = linesByEntry.getOrDefault(entry.id, List.of());
                String canonicalPayload = HashChain.canonicalPayload(entry.referenceId, entry.description, entry.postedAt, lines);
                String recomputedPayloadHash = HashChain.entryPayloadHash(canonicalPayload);

                if (!recomputedPayloadHash.equals(entry.entryPayloadHash)) {
                    output.put("valid", false);
                    output.put("entries_verified", verified);
                    output.put("breach", breach(entry.sequenceNumber, "payload_hash_mismatch",
                            "Recomputed entry_payload_hash does not match the stored value — the entry's metadata or lines were altered out-of-band.",
                            "expected", recomputedPayloadHash, "stored", entry.entryPayloadHash));
                    OutputProcessor.send(res, 200, output);
                    return;
                }

                String recomputedBlockHash = HashChain.blockHash(recomputedPayloadHash, runningHash);
                if (!recomputedBlockHash.equals(entry.blockHash)) {
                    output.put("valid", false);
                    output.put("entries_verified", verified);
                    output.put("breach", breach(entry.sequenceNumber, "block_hash_mismatch",
                            "Recomputed block_hash does not match the stored value.",
                            "expected", recomputedBlockHash, "stored", entry.blockHash));
                    OutputProcessor.send(res, 200, output);
                    return;
                }

                runningHash = entry.blockHash;
                expectedSequence++;
                verified++;
            }

            output.put("valid", true);
            output.put("entries_verified", verified);
            output.put("last_block_hash", runningHash);
            OutputProcessor.send(res, 200, output);
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    private JSONObject breach(long sequenceNumber, String reason, String message, String... detailKvPairs) {
        JSONObject b = new JSONObject();
        b.put("sequence_number", sequenceNumber);
        b.put("reason", reason);
        b.put("message", message);
        JSONObject details = new JSONObject();
        for (int i = 0; i + 1 < detailKvPairs.length; i += 2) {
            details.put(detailKvPairs[i], detailKvPairs[i + 1]);
        }
        b.put("details", details);
        return b;
    }

    private List<EntryRow> fetchEntries(Connection conn, UUID appId) throws SQLException {
        List<EntryRow> entries = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT id, sequence_number, reference_id, description, posted_at, " +
                "entry_payload_hash, previous_block_hash, block_hash FROM journal_entries " +
                "WHERE app_id = ? ORDER BY sequence_number ASC")) {
            pstmt.setObject(1, appId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    EntryRow e = new EntryRow();
                    e.id = UUID.fromString(rs.getString("id"));
                    e.sequenceNumber = rs.getLong("sequence_number");
                    e.referenceId = rs.getString("reference_id");
                    e.description = rs.getString("description");
                    e.postedAt = rs.getTimestamp("posted_at").toInstant();
                    e.entryPayloadHash = rs.getString("entry_payload_hash");
                    e.previousBlockHash = rs.getString("previous_block_hash");
                    e.blockHash = rs.getString("block_hash");
                    entries.add(e);
                }
            }
        }
        return entries;
    }

    private Map<UUID, List<HashChain.Line>> fetchLinesByEntry(Connection conn, List<EntryRow> entries) throws SQLException {
        Map<UUID, List<HashChain.Line>> byEntry = new HashMap<>();
        UUID[] entryIds = new UUID[entries.size()];
        for (int i = 0; i < entries.size(); i++) entryIds[i] = entries.get(i).id;

        Array entryIdArray = conn.createArrayOf("uuid", entryIds);
        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT el.entry_id, a.account_code, el.line_type, el.amount FROM entry_lines el " +
                "JOIN accounts a ON a.id = el.account_id WHERE el.entry_id = ANY(?)")) {
            pstmt.setArray(1, entryIdArray);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    UUID entryId = UUID.fromString(rs.getString("entry_id"));
                    HashChain.Line line = new HashChain.Line(
                            rs.getString("account_code"), rs.getString("line_type"), rs.getBigDecimal("amount"));
                    byEntry.computeIfAbsent(entryId, k -> new ArrayList<>()).add(line);
                }
            }
        }
        return byEntry;
    }
}
