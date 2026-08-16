# Triple-Entry Accounting: Design Doc

## Status

Draft. No code changes yet. This document defines the target shape referenced
by the README's "Ready for triple-entry" capability and is the spec to
implement against once prioritized.

## 1. Background

Double-entry accounting (what TSI Ledger implements today) guarantees a
ledger is *internally* consistent: every entry's debits equal its credits,
and `HashChain.java`'s per-App hash chain (`block_hash = SHA256(entry_payload_hash + previous_block_hash)`)
guarantees the ledger is *internally* tamper-evident, i.e. nobody can
rewrite history without breaking the chain that `Audit.verify_audit` walks.

Neither guarantee helps a counterparty, an auditor, or a regulator who
doesn't already trust the App that posted the entry. They can confirm the
ledger wasn't altered *after the fact*, but they have no way to confirm the
entry reflects a real, mutually-agreed transaction rather than something one
party fabricated unilaterally. That's the gap triple-entry accounting (Ian
Grigg's term) closes: a third, independently-verifiable record of the
transaction that exists outside the posting party's own database, signed by
both sides of the transaction.

Concretely: today, if `apps` row "Commerce" posts a settlement entry against
"Vendor Portal", nothing in `journal_entries` proves Vendor Portal agreed
that the transaction happened. Triple-entry adds that proof.

## 2. Goals

1. Every journal entry can carry a cryptographic signature proving *who*
   posted it, over data that can't be altered without invalidating the
   signature (extends the existing hash chain rather than replacing it).
2. A transaction between two registered counterparties (two `apps` rows, or
   an App and an external party) can be countersigned by the counterparty,
   producing a receipt that is independently verifiable by a fourth party
   who trusts neither side.
3. `verify_audit` extends naturally to also verify signatures and receipts,
   without changing its existing hash-chain semantics.
4. None of this is required for a single-party ledger. An App that never
   registers a signing key or a counterparty behaves exactly as it does
   today; this is additive, not a breaking change to `post_entry`.

## Non-goals

- Not building a blockchain, a consensus protocol, or a distributed ledger
  shared by multiple TSI Ledger deployments. "Network ledger" here means an
  external anchor/notarization point (Section 5.4), not a replicated chain.
- Not handling key custody/HSM integration. Apps generate and hold their own
  keypairs; TSI Ledger only ever sees and stores public keys and signatures.
- Not a settlement or payment-execution protocol. Triple-entry proves a
  transaction was mutually witnessed; it does not move money.

## 3. Conceptual model

Three records per triple-entry transaction, all deriving from the same
`journal_entries` row that exists today:

1. **The entry itself** (exists today): the poster's own double-entry
   journal entry, hash-chained as it is now.
2. **The signature** (new): the poster signs `block_hash` with their
   registered private key. Proves *this specific App* posted *this exact,
   already-hash-chained* entry, and that the entry hasn't been altered since
   (the signature covers the hash chain, so tampering with the payload
   breaks the signature the same way it already breaks the chain).
3. **The counterparty receipt** (new): if the entry names a counterparty,
   that counterparty independently signs the same `block_hash`, producing a
   record that neither party controls unilaterally. This is the "third
   entry": not a third ledger line, but a third signature that exists
   outside the poster's own hash chain.

An optional fourth element, **network anchoring** (Section 5.4), periodically
publishes a Merkle root of recent `block_hash` values to an external,
neutral location, so that even TSI Ledger's own database being compromised
can't retroactively rewrite history that's already been anchored.

## 4. Why this fits the existing schema

`HashChain.canonicalPayload` already excludes nothing that matters and
includes everything that would need to be true for a signature to be
meaningful: `reference_id`, `description`, `posted_at`, and the sorted
`lines`. Signing `block_hash` (rather than the raw payload) is deliberate: it
transitively commits to the entire chain up to that point via
`previous_block_hash`, so a valid signature on entry N is also indirect
evidence about the integrity of entries 1..N-1 for that App.

This means triple-entry is purely additive to `journal_entries`: no
existing column changes meaning, and `post_entry`/`verify_audit`'s current
behavior for entries with no signature or counterparty is unchanged.

## 5. Schema changes

New file `db/02_triple_entry.sql`, applied after `01_init.sql`.

### 5.1 `signing_keys` (registered public keys)

```sql
CREATE TABLE signing_keys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_type VARCHAR(4) NOT NULL CHECK (owner_type IN ('APP', 'OPERATOR')),
    owner_id UUID NOT NULL,
    public_key CHAR(64) NOT NULL,           -- Ed25519 public key, hex-encoded
    algorithm VARCHAR(16) NOT NULL DEFAULT 'Ed25519',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REVOKED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    revoked_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_signing_keys_owner ON signing_keys(owner_type, owner_id, status);
```

Keys are generated client-side. The private key never reaches the server;
only the public key is registered, via the new `register_signing_key`
function (Section 6). This mirrors how `apps.api_key_*_hash` already stores
only a hash of the credential, never the credential itself.

`owner_type`/`owner_id` rather than a plain FK lets the same table serve both
Apps (posting sources) and Operators (for console-initiated manual entries
that also need to be signable) without two near-identical tables.

### 5.2 `journal_entries` additions

```sql
ALTER TABLE journal_entries
    ADD COLUMN signing_key_id UUID REFERENCES signing_keys(id),
    ADD COLUMN entry_signature VARCHAR(128),        -- Ed25519 signature, hex-encoded
    ADD COLUMN counterparty_app_id UUID REFERENCES apps(id);
```

All three are nullable. `counterparty_app_id` is who the entry claims to be
a transaction with; it's set by the poster at `post_entry` time and is what
`submit_receipt` (Section 6) checks the countersigner against.

### 5.3 `entry_receipts` (counterparty countersignatures)

```sql
CREATE TABLE entry_receipts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entry_id UUID NOT NULL REFERENCES journal_entries(id) ON DELETE RESTRICT,
    countersigner_app_id UUID NOT NULL REFERENCES apps(id),
    signing_key_id UUID NOT NULL REFERENCES signing_keys(id),
    receipt_signature VARCHAR(128) NOT NULL,        -- signature over the same block_hash
    status VARCHAR(16) NOT NULL DEFAULT 'CONFIRMED' CHECK (status IN ('CONFIRMED', 'DISPUTED')),
    dispute_reason TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_entry_receipt UNIQUE (entry_id, countersigner_app_id)
);
CREATE INDEX idx_entry_receipts_entry ON entry_receipts(entry_id);
```

A receipt can be `DISPUTED` rather than only present/absent: the
counterparty is an independent party and disagreeing about whether a posted
entry reflects the real transaction is a legitimate, expected outcome, not
an error. A disputed receipt does not roll back or reverse the original
entry (nothing about the immutability model changes); it's a signed,
equally permanent record that the counterparty contests it, which is exactly
the kind of evidence triple-entry exists to preserve. Resolving a dispute
happens the same way any correction does today: via `request_reversal`.

### 5.4 `network_anchors` (external notarization, later phase)

```sql
CREATE TABLE network_anchors (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    period_start TIMESTAMP WITH TIME ZONE NOT NULL,
    period_end TIMESTAMP WITH TIME ZONE NOT NULL,
    merkle_root CHAR(64) NOT NULL,
    entry_count INTEGER NOT NULL,
    anchor_target VARCHAR(64) NOT NULL,             -- e.g. 'OPENTIMESTAMPS', 'MANUAL_NOTARY'
    anchor_reference TEXT,                          -- external proof/receipt (tx id, timestamp proof, etc.)
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
```

Deliberately vendor-agnostic: `anchor_target` names whatever external
mechanism actually gets used (a timestamping service, a periodic signed
publication, a third-party notary), and this table only records that it
happened and holds the proof, not how it happened. Not part of the initial
implementation; see Section 8.

## 6. API changes

New `_func` values, one new module (`signing_keys`) plus additions to two
existing ones. Each new function gets its own JSON Schema in
`web/WEB-INF/validator/`, per the existing pattern.

| Module | Function | Auth mode | Notes |
|--------|----------|-----------|-------|
| `signing_keys` (new) | `register_signing_key` | `TENANT_OR_CONSOLE` | App registers its own public key, or an Admin registers one for an Operator |
| `signing_keys` | `list_signing_keys` | `TENANT_OR_CONSOLE` | |
| `signing_keys` | `revoke_signing_key` | `TENANT_OR_CONSOLE` | Owner-only; sets `status='REVOKED'`, does not delete (past signatures must remain verifiable) |
| `ledger` | `post_entry` (extends existing) | `TENANT_OR_CONSOLE` | Optional `signing_key_id`, `signature`, `counterparty_app_id` fields. Server verifies the signature against the registered public key before accepting the entry; a bad signature is a 400, same tier as an unbalanced entry today |
| `ledger` | `submit_receipt` (new) | `TENANT_OR_CONSOLE` | Counterparty signs `block_hash` of an entry naming them as `counterparty_app_id`; inserts into `entry_receipts` |
| `ledger` | `dispute_receipt` (new) | `TENANT_OR_CONSOLE` | Counterparty marks an entry `DISPUTED` with a reason, still requires their signature |
| `audit` | `verify_audit` (extends existing) | `TENANT_OR_CONSOLE` | Adds signature and receipt verification to the existing hash-chain walk; reports them as separate pass/fail facets so a break in one doesn't mask the other |

Signing happens client-side: the App/Operator computes `block_hash` exactly
as the server does (this is why `HashChain`'s canonical serialization is
already fully specified and deterministic), signs it with their private
key, and sends the signature alongside the normal `post_entry` payload. The
server recomputes `block_hash` itself and verifies the signature against it,
the same trust boundary as `entry_payload_hash` today: the server is
authoritative on what the hash *is*, the caller only proves they hold the
key that signed it.

## 7. Verification flow

`verify_audit` today walks each App's chain and recomputes
`entry_payload_hash`/`block_hash` from stored data, flagging any row where
the stored hash doesn't match. Triple-entry adds two independent checks per
entry, reported separately:

1. **Signature check** (if `entry_signature` is set): recompute `block_hash`,
   verify it against `signing_keys.public_key` for `signing_key_id`. A
   revoked key still verifies past signatures (revocation stops *future*
   signing, it doesn't invalidate history); `verify_audit` should flag
   "signed with a now-revoked key" as informational, not a failure.
2. **Receipt check** (if `counterparty_app_id` is set): confirm an
   `entry_receipts` row exists for that entry/counterparty pair, verify its
   signature the same way, and surface its `status` (`CONFIRMED`/`DISPUTED`/
   missing entirely; a counterparty has had no obligation to respond yet).

An entry with no signature and no counterparty verifies exactly as it does
today: pure double-entry, no triple-entry claims made or checked.

## 8. Phasing

- **Phase 1: signed entries.** `signing_keys`, `post_entry` signature
  fields, `verify_audit` signature check. No counterparty concept yet. This
  alone lets an App prove authorship of its own entries to a third party
  without needing a counterparty relationship, and is independently useful.
- **Phase 2: counterparty receipts.** `counterparty_app_id`,
  `entry_receipts`, `submit_receipt`/`dispute_receipt`, `verify_audit`
  receipt check. This is "triple-entry" proper.
- **Phase 3: network anchoring.** `network_anchors`, a scheduled job
  publishing periodic Merkle roots externally. Deferred: needs a concrete
  choice of external anchor target, which is a product/ops decision, not
  just an engineering one.

Phase 1 and 2 require no migration of existing data: all new columns are
nullable and all new tables are additive. Nothing about `post_entry`'s
current behavior changes for a caller that doesn't opt in.

## 9. Open questions

1. **Cross-deployment counterparties.** `counterparty_app_id` as specified
   only works within one TSI Ledger deployment (both parties are `apps` rows
   in the same database). A counterparty running their *own* TSI Ledger
   instance, or no ledger at all, needs either a federation mechanism
   (out of scope here) or a lighter-weight external-counterparty record
   (e.g. just their public key, no `apps` row), deferred to whenever a real
   cross-organization use case is prioritized.
2. **Key rotation UX.** Multiple concurrent `ACTIVE` keys per owner are
   already allowed by the schema (no partial-unique constraint restricting
   to one), which is intentional (in-flight signing during a rotation
   shouldn't hard-fail), but the console needs a clear "current key" concept
   before this ships.
3. **Signature algorithm choice.** Ed25519 is assumed above for its speed and
   small key/signature size, but the concrete library choice affects the
   `pom.xml` dependency list (nothing currently in the project does asymmetric
   crypto; `jjwt`/`jbcrypt` are HMAC/password-hashing only) and should be
   confirmed before Phase 1 implementation starts.
