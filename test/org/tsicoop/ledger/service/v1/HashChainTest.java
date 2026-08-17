package org.tsicoop.ledger.service.v1;

import org.junit.Test;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Pure unit tests for HashChain — no DB, no servlet container. This is the
 * single source of truth both post_entry and verify_audit derive P_n/H_n
 * from (see HashChain's class doc), so its determinism and sensitivity to
 * every input field is exactly what the hash-chain integrity claim rests on.
 */
public class HashChainTest {

    private static HashChain.Line line(String code, String type, String amount) {
        return new HashChain.Line(code, type, new BigDecimal(amount));
    }

    private static List<HashChain.Line> sampleLines() {
        return Arrays.asList(
                line("CASH", "DEBIT", "100.0000"),
                line("REVENUE", "CREDIT", "100.0000"));
    }

    // ---------- canonical payload determinism ----------

    @Test
    public void canonicalPayload_isDeterministic_acrossRepeatedCalls() {
        Instant postedAt = Instant.parse("2026-01-15T10:00:00Z");
        String p1 = HashChain.canonicalPayload("ref-1", "desc", postedAt, sampleLines());
        String p2 = HashChain.canonicalPayload("ref-1", "desc", postedAt, sampleLines());
        assertEquals(p1, p2);
        assertEquals(HashChain.entryPayloadHash(p1), HashChain.entryPayloadHash(p2));
    }

    @Test
    public void canonicalPayload_isIndependentOfSubmittedLineOrder() {
        Instant postedAt = Instant.parse("2026-01-15T10:00:00Z");
        List<HashChain.Line> forward = Arrays.asList(
                line("CASH", "DEBIT", "100.0000"),
                line("REVENUE", "CREDIT", "100.0000"));
        List<HashChain.Line> reversed = Arrays.asList(
                line("REVENUE", "CREDIT", "100.0000"),
                line("CASH", "DEBIT", "100.0000"));

        String p1 = HashChain.canonicalPayload("ref-1", "desc", postedAt, forward);
        String p2 = HashChain.canonicalPayload("ref-1", "desc", postedAt, reversed);

        assertEquals("submission order must not affect the canonical form, since entry_lines has no order column",
                p1, p2);
    }

    @Test
    public void canonicalPayload_changesWhenReferenceIdChanges() {
        Instant postedAt = Instant.parse("2026-01-15T10:00:00Z");
        String p1 = HashChain.canonicalPayload("ref-1", "desc", postedAt, sampleLines());
        String p2 = HashChain.canonicalPayload("ref-2", "desc", postedAt, sampleLines());
        assertNotEquals(p1, p2);
        assertNotEquals(HashChain.entryPayloadHash(p1), HashChain.entryPayloadHash(p2));
    }

    @Test
    public void canonicalPayload_changesWhenDescriptionChanges() {
        Instant postedAt = Instant.parse("2026-01-15T10:00:00Z");
        String p1 = HashChain.canonicalPayload("ref-1", "desc-a", postedAt, sampleLines());
        String p2 = HashChain.canonicalPayload("ref-1", "desc-b", postedAt, sampleLines());
        assertNotEquals(p1, p2);
    }

    @Test
    public void canonicalPayload_changesWhenPostedAtChanges() {
        String p1 = HashChain.canonicalPayload("ref-1", "desc", Instant.parse("2026-01-15T10:00:00Z"), sampleLines());
        String p2 = HashChain.canonicalPayload("ref-1", "desc", Instant.parse("2026-01-15T10:00:01Z"), sampleLines());
        assertNotEquals(p1, p2);
    }

    @Test
    public void canonicalPayload_changesWhenLineAmountChanges() {
        Instant postedAt = Instant.parse("2026-01-15T10:00:00Z");
        List<HashChain.Line> lines1 = Arrays.asList(line("CASH", "DEBIT", "100.0000"), line("REVENUE", "CREDIT", "100.0000"));
        List<HashChain.Line> lines2 = Arrays.asList(line("CASH", "DEBIT", "100.0001"), line("REVENUE", "CREDIT", "100.0001"));
        assertNotEquals(HashChain.canonicalPayload("ref-1", "desc", postedAt, lines1),
                HashChain.canonicalPayload("ref-1", "desc", postedAt, lines2));
    }

    @Test
    public void canonicalPayload_changesWhenLineAccountOrTypeChanges() {
        Instant postedAt = Instant.parse("2026-01-15T10:00:00Z");
        List<HashChain.Line> base = Arrays.asList(line("CASH", "DEBIT", "100.0000"), line("REVENUE", "CREDIT", "100.0000"));
        List<HashChain.Line> swappedAccount = Arrays.asList(line("BANK", "DEBIT", "100.0000"), line("REVENUE", "CREDIT", "100.0000"));
        List<HashChain.Line> swappedType = Arrays.asList(line("CASH", "CREDIT", "100.0000"), line("REVENUE", "DEBIT", "100.0000"));

        String basePayload = HashChain.canonicalPayload("ref-1", "desc", postedAt, base);
        assertNotEquals(basePayload, HashChain.canonicalPayload("ref-1", "desc", postedAt, swappedAccount));
        assertNotEquals(basePayload, HashChain.canonicalPayload("ref-1", "desc", postedAt, swappedType));
    }

    // ---------- payload/block hash formula ----------

    @Test
    public void entryPayloadHash_matchesIndependentSha256Computation() throws Exception {
        String payload = HashChain.canonicalPayload("ref-1", "desc", Instant.parse("2026-01-15T10:00:00Z"), sampleLines());
        String expected = sha256Hex(payload);
        assertEquals(expected, HashChain.entryPayloadHash(payload));
        assertEquals(64, HashChain.entryPayloadHash(payload).length());
    }

    @Test
    public void blockHash_isSha256OfPayloadHashConcatenatedWithPreviousBlockHash() throws Exception {
        String payloadHash = "a".repeat(64);
        String previousHash = "b".repeat(64);
        String expected = sha256Hex(payloadHash + previousHash);
        assertEquals(expected, HashChain.blockHash(payloadHash, previousHash));
    }

    @Test
    public void genesisHash_is64ZeroCharacters() {
        assertEquals(64, HashChain.GENESIS_HASH.length());
        assertEquals("0".repeat(64), HashChain.GENESIS_HASH);
    }

    // ---------- chain continuation ----------

    @Test
    public void chain_ofThreeEntries_recomputesIdenticallyOnASecondPass() {
        // Simulates what verify_audit does: given the same stored payload fields in
        // order, independently re-derive P_n/H_n and confirm they land on the exact
        // same chain both times — that reproducibility is the whole integrity claim.
        Instant t0 = Instant.parse("2026-01-15T10:00:00Z");
        Object[][] entries = {
                {"ref-1", "first",  Arrays.asList(line("CASH", "DEBIT", "100.0000"), line("REVENUE", "CREDIT", "100.0000"))},
                {"ref-2", "second", Arrays.asList(line("CASH", "DEBIT", "50.0000"), line("REVENUE", "CREDIT", "50.0000"))},
                {"ref-3", "third",  Arrays.asList(line("EXPENSE", "DEBIT", "25.0000"), line("CASH", "CREDIT", "25.0000"))},
        };

        List<String> pass1 = computeChain(entries, t0);
        List<String> pass2 = computeChain(entries, t0);

        assertEquals("recomputing the chain from the same stored fields must be deterministic", pass1, pass2);

        // Chain continuity: each block's hash folds in the previous block's hash,
        // and the first entry chains from the genesis hash.
        assertEquals(HashChain.GENESIS_HASH, chainOf(entries, t0, 0).previousBlockHash);
        for (int i = 1; i < entries.length; i++) {
            assertEquals("entry " + i + " must chain from entry " + (i - 1) + "'s block hash",
                    pass1.get(i - 1), chainOf(entries, t0, i).previousBlockHash);
        }
    }

    @Test
    public void chain_tamperingWithAnEarlierEntry_breaksEveryLaterBlockHash() {
        Instant t0 = Instant.parse("2026-01-15T10:00:00Z");
        Object[][] original = {
                {"ref-1", "first",  Arrays.asList(line("CASH", "DEBIT", "100.0000"), line("REVENUE", "CREDIT", "100.0000"))},
                {"ref-2", "second", Arrays.asList(line("CASH", "DEBIT", "50.0000"), line("REVENUE", "CREDIT", "50.0000"))},
        };
        Object[][] tampered = {
                {"ref-1", "first",  Arrays.asList(line("CASH", "DEBIT", "999.0000"), line("REVENUE", "CREDIT", "999.0000"))}, // amount tampered
                {"ref-2", "second", Arrays.asList(line("CASH", "DEBIT", "50.0000"), line("REVENUE", "CREDIT", "50.0000"))},
        };

        List<String> originalChain = computeChain(original, t0);
        List<String> tamperedChain = computeChain(tampered, t0);

        assertNotEquals("tampering entry 0's amount must change its own block hash",
                originalChain.get(0), tamperedChain.get(0));
        assertNotEquals("...and must therefore also change entry 1's block hash, since it folds in entry 0's hash",
                originalChain.get(1), tamperedChain.get(1));
    }

    // ---------- description/reference_id cannot break out of the JSON string ----------

    @Test
    public void canonicalPayload_escapesQuotesAndControlCharactersSafely() throws Exception {
        String maliciousDescription = "a\",\"reference_id\":\"forged";
        String payload = HashChain.canonicalPayload("ref-1", maliciousDescription,
                Instant.parse("2026-01-15T10:00:00Z"), sampleLines());

        // Must still parse as valid JSON, and the injected text must stay inside the
        // description field's string value rather than opening a sibling key.
        com.fasterxml.jackson.databind.JsonNode node =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
        assertEquals(maliciousDescription, node.get("description").asText());
        assertEquals("ref-1", node.get("reference_id").asText());
    }

    @Test
    public void canonicalPayload_escapesNewlinesTabsAndUnicode() throws Exception {
        String description = "line one\nline two\ttabbed éè emoji 😀";
        String payload = HashChain.canonicalPayload("ref-1", description,
                Instant.parse("2026-01-15T10:00:00Z"), sampleLines());

        com.fasterxml.jackson.databind.JsonNode node =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
        assertEquals(description, node.get("description").asText());
    }

    // ---------- helpers ----------

    private static List<String> computeChain(Object[][] entries, Instant t0) {
        List<String> blockHashes = new java.util.ArrayList<>();
        String previous = HashChain.GENESIS_HASH;
        for (int i = 0; i < entries.length; i++) {
            @SuppressWarnings("unchecked")
            List<HashChain.Line> lines = (List<HashChain.Line>) entries[i][2];
            String payload = HashChain.canonicalPayload((String) entries[i][0], (String) entries[i][1], t0.plusSeconds(i), lines);
            String payloadHash = HashChain.entryPayloadHash(payload);
            String blockHash = HashChain.blockHash(payloadHash, previous);
            blockHashes.add(blockHash);
            previous = blockHash;
        }
        return blockHashes;
    }

    private static final class ChainEntry {
        final String previousBlockHash;
        ChainEntry(String previousBlockHash) { this.previousBlockHash = previousBlockHash; }
    }

    private static ChainEntry chainOf(Object[][] entries, Instant t0, int index) {
        String previous = HashChain.GENESIS_HASH;
        for (int i = 0; i < index; i++) {
            @SuppressWarnings("unchecked")
            List<HashChain.Line> lines = (List<HashChain.Line>) entries[i][2];
            String payload = HashChain.canonicalPayload((String) entries[i][0], (String) entries[i][1], t0.plusSeconds(i), lines);
            previous = HashChain.blockHash(HashChain.entryPayloadHash(payload), previous);
        }
        return new ChainEntry(previous);
    }

    private static String sha256Hex(String raw) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
