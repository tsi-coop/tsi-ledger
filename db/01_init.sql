-- TSI Ledger Service — schema
-- PostgreSQL 15. gen_random_uuid() is available natively since PG13, no
-- extension required. Floating-point types are never used for money — all
-- amounts are NUMERIC(18,4) per the FSD's "no floats" invariant.

-- 1. Tenant container. Each "App" is a fully isolated ledger namespace with
-- its own chart of accounts, journal sequence, and hash chain (FSD Section
-- 2/3). api_key_*_hash store a SHA-256 hex digest of the raw key — a
-- deterministic hash, not bcrypt, because the auth path does a direct index
-- lookup by hash rather than a per-row secret comparison (see
-- InputProcessor.resolveTenantApp).
CREATE TABLE apps (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    app_name VARCHAR(255) NOT NULL,
    api_key_live_hash CHAR(64) UNIQUE,
    api_key_test_hash CHAR(64) UNIQUE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- 2. Chart of Accounts. account_code is app-scoped (two different Apps may
-- both use "1010"), not globally unique.
CREATE TABLE accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    app_id UUID NOT NULL REFERENCES apps(id) ON DELETE RESTRICT,
    account_code VARCHAR(32) NOT NULL,
    account_name VARCHAR(128) NOT NULL,
    account_type VARCHAR(16) NOT NULL CHECK (account_type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_app_account_code UNIQUE (app_id, account_code)
);
CREATE INDEX idx_accounts_app ON accounts(app_id);

-- 3. Journal Entries — the hash-chained block record (FSD Section 5 / TDD
-- Section 5): entry_payload_hash = P_n, previous_block_hash = H_(n-1),
-- block_hash = H_n. sequence_number is gapless and strictly increasing per
-- app_id, genesis previous_block_hash is 64 '0' characters.
--
-- Concurrency safety: post_entry (Step 5) must `SELECT ... FOR UPDATE` the
-- parent apps row before computing the next sequence_number/block_hash,
-- inside the same transaction as this insert — otherwise two concurrent
-- posts for the same app can race and either duplicate a sequence_number or
-- branch the hash chain. Different apps post concurrently without
-- contention since they lock different apps rows.
--
-- idempotency_key persists the mandatory Idempotency-Key header so a retried
-- POST /v1/entries can be detected and answered from the original row instead
-- of double-posting (FSD Section 4, item 3) — kept as a DB-durable column
-- rather than an in-memory cache so it survives restarts and works across
-- multiple app instances.
CREATE TABLE journal_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    app_id UUID NOT NULL REFERENCES apps(id) ON DELETE RESTRICT,
    sequence_number BIGINT NOT NULL,
    reference_id VARCHAR(128) NOT NULL,
    description TEXT NOT NULL,
    posted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    entry_payload_hash CHARACTER(64) NOT NULL,
    previous_block_hash CHARACTER(64) NOT NULL,
    block_hash CHARACTER(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_app_sequence UNIQUE (app_id, sequence_number),
    CONSTRAINT uq_app_idempotency_key UNIQUE (app_id, idempotency_key)
);
CREATE INDEX idx_entries_audit_chain ON journal_entries(app_id, sequence_number DESC);

-- 4. Entry Lines — individual debit/credit legs of a journal entry. The
-- double-entry invariant (sum(DEBIT) = sum(CREDIT) per entry_id) is enforced
-- in application code at post time (Step 5), not by a DB constraint, since
-- Postgres has no cross-row CHECK.
CREATE TABLE entry_lines (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entry_id UUID NOT NULL REFERENCES journal_entries(id) ON DELETE RESTRICT,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    line_type VARCHAR(6) NOT NULL CHECK (line_type IN ('DEBIT', 'CREDIT')),
    amount NUMERIC(18, 4) NOT NULL CHECK (amount > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_entry_lines_entry ON entry_lines(entry_id);
CREATE INDEX idx_entry_lines_account ON entry_lines(account_id);
