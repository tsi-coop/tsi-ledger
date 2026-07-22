package org.tsicoop.ledger.service.v1;

import org.tsicoop.ledger.framework.InputProcessor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Deterministic serialization + hash-chain math shared by post_entry (Step 5)
 * and verify_audit (Step 6) — both must derive identical P_n/H_n from the
 * same stored data, so this is the single source of truth for the algorithm
 * (FSD Section 5 / TDD Section 5):
 *
 *   P_n = SHA256(CanonicalJSON(reference_id, description, posted_at, lines))
 *   H_n = SHA256(P_n + H_(n-1)),  H_0 = 64 '0' characters (genesis)
 *
 * CanonicalJSON is hand-built, not json-simple's toJSONString() (backed by a
 * HashMap — no stable key order) or Jackson's (no contractual key-order
 * guarantee either): fixed alphabetical key order, no whitespace, amounts
 * rendered as plain 4-decimal strings (matching entry_lines.amount's
 * NUMERIC(18,4) storage), posted_at rendered via Instant.toString() (matching
 * what a TIMESTAMPTZ column round-trips to, independent of how the client
 * originally formatted the timestamp in the request).
 *
 * `lines` is sorted by (account_code, type, amount) before serializing rather
 * than trusting submission/storage order — entry_lines has no order column,
 * and Postgres's now() default is the transaction start time, so rows
 * inserted in one transaction share a created_at and cannot be reliably
 * re-ordered by it. Sorting deterministically at hash time (both here and in
 * verify_audit) sidesteps the need for one.
 */
public class HashChain {

    public static final String GENESIS_HASH = "0".repeat(64);

    private static final Comparator<Line> LINE_ORDER =
            Comparator.comparing((Line l) -> l.accountCode)
                    .thenComparing(l -> l.type)
                    .thenComparing(l -> l.amount);

    public static class Line {
        public final String accountCode;
        public final String type;
        public final BigDecimal amount;

        public Line(String accountCode, String type, BigDecimal amount) {
            this.accountCode = accountCode;
            this.type = type;
            this.amount = amount;
        }
    }

    public static String canonicalPayload(String referenceId, String description, Instant postedAt, List<Line> lines) {
        List<Line> sorted = new ArrayList<>(lines);
        sorted.sort(LINE_ORDER);

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"description\":").append(jsonString(description)).append(",");
        sb.append("\"lines\":[");
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) sb.append(",");
            Line l = sorted.get(i);
            sb.append("{");
            sb.append("\"account_code\":").append(jsonString(l.accountCode)).append(",");
            sb.append("\"amount\":").append(jsonString(l.amount.setScale(4).toPlainString())).append(",");
            sb.append("\"type\":").append(jsonString(l.type));
            sb.append("}");
        }
        sb.append("],");
        sb.append("\"posted_at\":").append(jsonString(postedAt.toString())).append(",");
        sb.append("\"reference_id\":").append(jsonString(referenceId));
        sb.append("}");
        return sb.toString();
    }

    public static String entryPayloadHash(String canonicalPayload) {
        return InputProcessor.sha256Hex(canonicalPayload);
    }

    public static String blockHash(String payloadHash, String previousBlockHash) {
        return InputProcessor.sha256Hex(payloadHash + previousBlockHash);
    }

    private static String jsonString(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 2);
        sb.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private HashChain() {}
}
