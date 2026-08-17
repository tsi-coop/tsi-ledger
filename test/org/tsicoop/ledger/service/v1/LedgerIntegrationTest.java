package org.tsicoop.ledger.service.v1;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.tsicoop.ledger.framework.PoolDB;
import org.tsicoop.ledger.framework.SystemConfig;
import org.tsicoop.ledger.service.v1.support.FakeHttp;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Integration tests for Ledger.post_entry/reverse_entry against a real
 * Postgres — the balance check, idempotency replay, and concurrent-post
 * locking behavior PENDING.md #2 calls out all live in SQL (FOR UPDATE row
 * locking, a UNIQUE(app_id, idempotency_key) lookup, the debit=credit
 * comparison against parsed lines) and can't be verified by a pure unit
 * test the way HashChainTest covers the hashing math.
 *
 * Drives Ledger.post(req, res) directly via the FakeHttp doubles — no
 * servlet container — against whatever Postgres POSTGRES_HOST points at
 * (the docker-compose dev database on localhost:5438 works: run with
 * POSTGRES_HOST=jdbc:postgresql://localhost:5438 POSTGRES_DB=tsi_ledger
 * POSTGRES_USER=tsi_ledger_admin POSTGRES_PASSWD=<from .env>
 * TSI_LEDGER_ENV=local). Skips itself (via Assume) rather than failing when
 * that isn't configured, so `mvn test` stays green with no DB available.
 * Each test creates and tears down its own App + Accounts fixtures under a
 * random suffix so it can run safely against a database with real data in it.
 */
public class LedgerIntegrationTest {

    private static final String IDEMPOTENCY_HEADER_NOTE =
            "Set POSTGRES_HOST (e.g. jdbc:postgresql://localhost:5438), POSTGRES_DB, POSTGRES_USER, " +
            "POSTGRES_PASSWD, and TSI_LEDGER_ENV=local to run Ledger integration tests against a real " +
            "Postgres (see docker-compose.yml for the dev database). Skipping.";

    private PoolDB fixturePool;
    private Connection conn;
    private UUID appId;
    private String acctA;
    private String acctB;
    private final List<UUID> fixtureOperatorIds = new ArrayList<>();

    @BeforeClass
    public static void checkDatabaseAvailable() {
        Assume.assumeTrue(IDEMPOTENCY_HEADER_NOTE, System.getenv("POSTGRES_HOST") != null);
        SystemConfig.loadAppConfig(null);
        try (Connection c = new PoolDB().getConnection()) {
            assertNotNull(c);
        } catch (Exception e) {
            Assume.assumeNoException(IDEMPOTENCY_HEADER_NOTE, e);
        }
    }

    @Before
    public void setUp() throws Exception {
        fixturePool = new PoolDB();
        conn = fixturePool.getConnection();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        appId = insertApp(conn, "ledger-test-" + suffix);
        acctA = "TST-A-" + suffix;
        acctB = "TST-B-" + suffix;
        insertAccount(conn, acctA, "Test Account A", "ASSET");
        insertAccount(conn, acctB, "Test Account B", "REVENUE");
    }

    @After
    public void tearDown() throws Exception {
        if (conn == null) return;
        try (PreparedStatement p = conn.prepareStatement(
                "DELETE FROM reversal_requests WHERE original_entry_id IN (SELECT id FROM journal_entries WHERE app_id = ?)")) {
            p.setObject(1, appId); p.executeUpdate();
        }
        try (PreparedStatement p = conn.prepareStatement(
                "DELETE FROM entry_lines WHERE entry_id IN (SELECT id FROM journal_entries WHERE app_id = ?)")) {
            p.setObject(1, appId); p.executeUpdate();
        }
        try (PreparedStatement p = conn.prepareStatement("DELETE FROM journal_entries WHERE app_id = ?")) {
            p.setObject(1, appId); p.executeUpdate();
        }
        try (PreparedStatement p = conn.prepareStatement("DELETE FROM accounts WHERE account_code IN (?, ?)")) {
            p.setString(1, acctA); p.setString(2, acctB); p.executeUpdate();
        }
        try (PreparedStatement p = conn.prepareStatement("DELETE FROM apps WHERE id = ?")) {
            p.setObject(1, appId); p.executeUpdate();
        }
        for (UUID operatorId : fixtureOperatorIds) {
            try (PreparedStatement p = conn.prepareStatement("DELETE FROM operators WHERE id = ?")) {
                p.setObject(1, operatorId); p.executeUpdate();
            }
        }
        conn.close();
    }

    // ---------- balance check ----------

    @Test
    public void postEntry_rejectsAnUnbalancedEntry() throws Exception {
        String body = postEntryBody("ref-unbalanced", "unbalanced",
                new Object[]{acctA, "DEBIT", "100.00"},
                new Object[]{acctB, "CREDIT", "90.00"});

        FakeHttp.CapturedResponse res = FakeHttp.response();
        new Ledger().post(FakeHttp.tenantRequest(body, appId, "idem-unbalanced-1"), res.asServletResponse());

        assertEquals(400, res.status());
        assertTrue(res.bodyJson().get("message").toString().contains("does not balance"));
        assertEquals("an unbalanced entry must not be persisted", 0, countEntries(conn, appId, "ref-unbalanced"));
    }

    @Test
    public void postEntry_acceptsABalancedEntry() throws Exception {
        String body = postEntryBody("ref-balanced", "balanced",
                new Object[]{acctA, "DEBIT", "100.00"},
                new Object[]{acctB, "CREDIT", "100.00"});

        FakeHttp.CapturedResponse res = FakeHttp.response();
        new Ledger().post(FakeHttp.tenantRequest(body, appId, "idem-balanced-1"), res.asServletResponse());

        assertEquals(201, res.status());
        assertEquals(1, countEntries(conn, appId, "ref-balanced"));
    }

    // ---------- idempotency replay ----------

    @Test
    public void postEntry_replayingTheSameIdempotencyKeyReturnsTheOriginalWithoutDoublePosting() throws Exception {
        String body = postEntryBody("ref-idem", "idempotency test",
                new Object[]{acctA, "DEBIT", "100.00"},
                new Object[]{acctB, "CREDIT", "100.00"});
        String idempotencyKey = "idem-replay-key";

        FakeHttp.CapturedResponse first = FakeHttp.response();
        new Ledger().post(FakeHttp.tenantRequest(body, appId, idempotencyKey), first.asServletResponse());
        assertEquals(201, first.status());
        String firstId = (String) first.bodyJson().get("id");
        assertEquals(Boolean.FALSE, first.bodyJson().get("idempotent_replay"));

        FakeHttp.CapturedResponse second = FakeHttp.response();
        new Ledger().post(FakeHttp.tenantRequest(body, appId, idempotencyKey), second.asServletResponse());
        assertEquals(200, second.status());
        assertEquals(firstId, second.bodyJson().get("id"));
        assertEquals(Boolean.TRUE, second.bodyJson().get("idempotent_replay"));

        assertEquals("a replayed Idempotency-Key must not create a second row",
                1, countEntries(conn, appId, "ref-idem"));
    }

    // ---------- concurrent-post locking ----------

    @Test
    public void postEntry_concurrentPostsToTheSameAppProduceAnUnbrokenSequentialChain() throws Exception {
        final int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            tasks.add(() -> {
                String body = postEntryBody("ref-conc-" + idx, "concurrent " + idx,
                        new Object[]{acctA, "DEBIT", String.valueOf(idx + 1) + ".00"},
                        new Object[]{acctB, "CREDIT", String.valueOf(idx + 1) + ".00"});
                FakeHttp.CapturedResponse res = FakeHttp.response();
                new Ledger().post(FakeHttp.tenantRequest(body, appId, "idem-conc-" + idx), res.asServletResponse());
                return res.status();
            });
        }

        List<Future<Integer>> futures = pool.invokeAll(tasks);
        pool.shutdown();
        assertTrue("all threads must finish", pool.awaitTermination(30, TimeUnit.SECONDS));
        for (Future<Integer> f : futures) {
            assertEquals("every concurrent post must succeed - none should be lost or rejected", 201, (int) f.get());
        }

        List<String[]> rows = fetchChainRows(conn, appId); // [sequence_number, block_hash, previous_block_hash]
        assertEquals("no sequence number may be lost or duplicated under concurrent posting", n, rows.size());
        for (int i = 0; i < n; i++) {
            assertEquals(String.valueOf(i + 1), rows.get(i)[0]);
        }
        assertEquals("the first entry in the app's chain must link to the genesis hash",
                HashChain.GENESIS_HASH, rows.get(0)[2]);
        for (int i = 1; i < n; i++) {
            assertEquals("entry " + (i + 1) + " must chain from entry " + i + "'s block hash - a broken link " +
                    "here means two concurrent posts raced past the FOR UPDATE app-row lock",
                    rows.get(i - 1)[1], rows.get(i)[2]);
        }
    }

    // ---------- reverse_entry ----------

    @Test
    public void reverseEntry_reversesTheOriginalAndBlocksASecondReversal() throws Exception {
        String body = postEntryBody("ref-to-reverse", "will be reversed",
                new Object[]{acctA, "DEBIT", "100.00"},
                new Object[]{acctB, "CREDIT", "100.00"});
        FakeHttp.CapturedResponse posted = FakeHttp.response();
        new Ledger().post(FakeHttp.tenantRequest(body, appId, "idem-to-reverse"), posted.asServletResponse());
        assertEquals(201, posted.status());
        String entryId = (String) posted.bodyJson().get("id");

        String reverseBody = reverseEntryBody(entryId);
        FakeHttp.CapturedResponse reversed = FakeHttp.response();
        new Ledger().post(FakeHttp.tenantRequest(reverseBody, appId, "idem-reverse-1"), reversed.asServletResponse());
        assertEquals(201, reversed.status());
        assertEquals("REVERSAL", reversed.bodyJson().get("entry_type"));
        assertEquals(entryId, reversed.bodyJson().get("reverses_entry_id"));

        JSONArray reversedLines = (JSONArray) reversed.bodyJson().get("lines");
        assertEquals(2, reversedLines.size());
        for (Object o : reversedLines) {
            JSONObject line = (JSONObject) o;
            String type = (String) line.get("type");
            String code = (String) line.get("account_code");
            if (acctA.equals(code)) assertEquals("the mirrored line must swap DEBIT<->CREDIT", "CREDIT", type);
            if (acctB.equals(code)) assertEquals("the mirrored line must swap DEBIT<->CREDIT", "DEBIT", type);
        }

        // A second reversal attempt on the same original entry must be blocked.
        FakeHttp.CapturedResponse secondReversal = FakeHttp.response();
        new Ledger().post(FakeHttp.tenantRequest(reverseBody, appId, "idem-reverse-2"), secondReversal.asServletResponse());
        assertEquals(409, secondReversal.status());

        assertEquals("original + reversal, no duplicate reversal", 2, countEntriesForApp(conn, appId));
        assertAuditValid(appId, 2);
    }

    // ---------- console maker-checker (request_reversal / approve_reversal) ----------

    /**
     * Regression test for a bug found while wiring up sample console data: approve_reversal
     * always 409'd, even on a fresh PENDING request with a different approver. Root cause -
     * validateReversible's "does this entry already have a pending/approved reversal request"
     * check matched the very row being approved, since it's still PENDING at that point in its
     * own transaction (only flips to APPROVED after the check passes). Fixed by having
     * handleApproveReversal pass its own requestId to validateReversible to exclude.
     */
    @Test
    public void approveReversal_succeedsForADifferentApproverThanTheRequester() throws Exception {
        String body = postEntryBody("ref-console-reversal", "console maker-checker test",
                new Object[]{acctA, "DEBIT", "100.00"},
                new Object[]{acctB, "CREDIT", "100.00"});
        FakeHttp.CapturedResponse posted = FakeHttp.response();
        new Ledger().post(FakeHttp.tenantRequest(body, appId, "idem-console-orig"), posted.asServletResponse());
        assertEquals(201, posted.status());
        String entryId = (String) posted.bodyJson().get("id");

        UUID makerId = insertOperator(conn, "Fixture Maker", "maker-" + UUID.randomUUID() + "@example.com", "OPERATOR");
        UUID checkerId = insertOperator(conn, "Fixture Checker", "checker-" + UUID.randomUUID() + "@example.com", "ADMIN");

        FakeHttp.CapturedResponse requestRes = FakeHttp.response();
        new Ledger().post(FakeHttp.consoleRequest(requestReversalBody(entryId, "duplicate posting"), makerId, "OPERATOR"),
                requestRes.asServletResponse());
        assertEquals(201, requestRes.status());
        assertEquals("PENDING", requestRes.bodyJson().get("status"));
        String requestId = (String) requestRes.bodyJson().get("id");

        String approveBody = approveReversalBody(requestId);

        // Same user cannot approve their own request, regardless of role.
        FakeHttp.CapturedResponse selfApprove = FakeHttp.response();
        new Ledger().post(FakeHttp.consoleRequest(approveBody, makerId, "ADMIN"), selfApprove.asServletResponse());
        assertEquals(403, selfApprove.status());

        // A different Admin approving is exactly the path that used to 409 on the request's own
        // still-PENDING row before the fix.
        FakeHttp.CapturedResponse approveRes = FakeHttp.response();
        new Ledger().post(FakeHttp.consoleRequest(approveBody, checkerId, "ADMIN"), approveRes.asServletResponse());
        assertEquals(200, approveRes.status());
        assertEquals("REVERSAL", approveRes.bodyJson().get("entry_type"));
        assertEquals(entryId, approveRes.bodyJson().get("reverses_entry_id"));

        // Approving an already-APPROVED request must still be blocked.
        FakeHttp.CapturedResponse secondApprove = FakeHttp.response();
        new Ledger().post(FakeHttp.consoleRequest(approveBody, checkerId, "ADMIN"), secondApprove.asServletResponse());
        assertEquals(409, secondApprove.status());

        // Regression coverage for the posted_at truncation bug found alongside this one: the
        // reversal entry's hash was computed from an untruncated Instant.now() but Postgres
        // silently rounds TIMESTAMPTZ to microseconds on insert, so verify_audit's recomputation
        // from the stored (truncated) value used to mismatch. See Ledger.postReversalEntry.
        assertAuditValid(appId, 2);
    }

    /** Drives Audit.verify_audit for real (the exact recomputation-from-stored-rows path that
     * surfaces posted_at truncation or other tamper-evidence bugs) and asserts it reports valid. */
    private void assertAuditValid(UUID appId, int expectedEntries) throws Exception {
        JSONObject body = new JSONObject();
        body.put("_func", "verify_audit");
        FakeHttp.CapturedResponse res = FakeHttp.response();
        new Audit().post(FakeHttp.tenantRequest(body.toJSONString(), appId, null), res.asServletResponse());
        assertEquals(200, res.status());
        JSONObject result = res.bodyJson();
        assertEquals("verify_audit breach: " + result, Boolean.TRUE, result.get("valid"));
        assertEquals((long) expectedEntries, ((Number) result.get("entries_verified")).longValue());
    }

    // ---------- fixture helpers ----------

    private UUID insertOperator(Connection conn, String name, String email, String role) throws Exception {
        try (PreparedStatement p = conn.prepareStatement(
                "INSERT INTO operators (name, email, password_hash, role) VALUES (?, ?, 'test-fixture-not-a-real-hash', ?) RETURNING id")) {
            p.setString(1, name);
            p.setString(2, email);
            p.setString(3, role);
            try (ResultSet rs = p.executeQuery()) {
                rs.next();
                UUID id = UUID.fromString(rs.getString(1));
                fixtureOperatorIds.add(id);
                return id;
            }
        }
    }

    private static UUID insertApp(Connection conn, String appName) throws Exception {
        try (PreparedStatement p = conn.prepareStatement("INSERT INTO apps (app_name) VALUES (?) RETURNING id")) {
            p.setString(1, appName);
            try (ResultSet rs = p.executeQuery()) {
                rs.next();
                return UUID.fromString(rs.getString(1));
            }
        }
    }

    private static void insertAccount(Connection conn, String code, String name, String type) throws Exception {
        try (PreparedStatement p = conn.prepareStatement(
                "INSERT INTO accounts (account_code, account_name, account_type) VALUES (?, ?, ?)")) {
            p.setString(1, code);
            p.setString(2, name);
            p.setString(3, type);
            p.executeUpdate();
        }
    }

    private static int countEntries(Connection conn, UUID appId, String referenceId) throws Exception {
        try (PreparedStatement p = conn.prepareStatement(
                "SELECT COUNT(*) FROM journal_entries WHERE app_id = ? AND reference_id = ?")) {
            p.setObject(1, appId);
            p.setString(2, referenceId);
            try (ResultSet rs = p.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static int countEntriesForApp(Connection conn, UUID appId) throws Exception {
        try (PreparedStatement p = conn.prepareStatement("SELECT COUNT(*) FROM journal_entries WHERE app_id = ?")) {
            p.setObject(1, appId);
            try (ResultSet rs = p.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Returns rows as [sequence_number, block_hash, previous_block_hash], ordered by sequence_number. */
    private static List<String[]> fetchChainRows(Connection conn, UUID appId) throws Exception {
        List<String[]> rows = new ArrayList<>();
        try (PreparedStatement p = conn.prepareStatement(
                "SELECT sequence_number, block_hash, previous_block_hash FROM journal_entries " +
                "WHERE app_id = ? ORDER BY sequence_number")) {
            p.setObject(1, appId);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    rows.add(new String[]{
                            String.valueOf(rs.getLong("sequence_number")),
                            rs.getString("block_hash"),
                            rs.getString("previous_block_hash")});
                }
            }
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    private static String postEntryBody(String referenceId, String description, Object[]... lines) {
        JSONObject body = new JSONObject();
        body.put("_func", "post_entry");
        body.put("reference_id", referenceId);
        body.put("description", description);
        body.put("posted_at", Instant.now().toString());

        JSONArray lineArray = new JSONArray();
        for (Object[] l : lines) {
            JSONObject line = new JSONObject();
            line.put("account_code", l[0]);
            line.put("type", l[1]);
            line.put("amount", new java.math.BigDecimal((String) l[2]));
            lineArray.add(line);
        }
        body.put("lines", lineArray);
        return body.toJSONString();
    }

    @SuppressWarnings("unchecked")
    private static String reverseEntryBody(String entryId) {
        JSONObject body = new JSONObject();
        body.put("_func", "reverse_entry");
        body.put("entry_id", entryId);
        return body.toJSONString();
    }

    @SuppressWarnings("unchecked")
    private static String requestReversalBody(String entryId, String reason) {
        JSONObject body = new JSONObject();
        body.put("_func", "request_reversal");
        body.put("entry_id", entryId);
        body.put("reason", reason);
        return body.toJSONString();
    }

    @SuppressWarnings("unchecked")
    private static String approveReversalBody(String reversalRequestId) {
        JSONObject body = new JSONObject();
        body.put("_func", "approve_reversal");
        body.put("reversal_request_id", reversalRequestId);
        return body.toJSONString();
    }
}
