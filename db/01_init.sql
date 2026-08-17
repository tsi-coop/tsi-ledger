-- TSI Ledger Service — schema
-- PostgreSQL 15. gen_random_uuid() is available natively since PG13, no
-- extension required. Floating-point types are never used for money — all
-- amounts are NUMERIC(18,4) per the FSD's "no floats" invariant.
--
-- Phase 2 shape: ONE organization-wide double-entry ledger. Apps (HRMS,
-- Commerce, Lending, ...) are posting sources that submit entries via their
-- own API key, but they all post against a single shared Chart of Accounts —
-- the `accounts` table is no longer app-scoped. Console users (operators)
-- log in with email/password instead of a shared admin master key.

-- 1. Console users (Ops Console accounts team): Admins and Operators.
-- Admins provision Apps/API keys, manage the Chart of Accounts structure,
-- manage other operators, and approve reversal requests. Operators browse
-- the ledger/COA, post manual entries, and request reversals (see
-- reversal_requests below for the maker-checker split).
-- reset_key_hash/reset_key_expires_at back the Admin-issued self-service
-- password reset key (Users.generate_reset_key / Auth.reset_with_key): an
-- Admin mints a one-time key for an Operator who is locked out, shares it
-- out of band, and the Operator redeems it themselves at a public console
-- page without the Admin ever having to know or set their new password.
-- Same hashed-lookup pattern as apps.api_key_*_hash - only the SHA-256 hash
-- is persisted, and it's cleared (single-use) as soon as it's redeemed.
CREATE TABLE operators (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(128) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(16) NOT NULL CHECK (role IN ('ADMIN', 'OPERATOR')),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    reset_key_hash CHAR(64),
    reset_key_expires_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_login_at TIMESTAMP WITH TIME ZONE,
    last_updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- 2. Apps: posting sources. Each App is a system (HRMS, Commerce, Lending, ...)
-- that submits journal entries via its own API key. api_key_*_hash store a
-- SHA-256 hex digest of the raw key — a deterministic hash, not bcrypt,
-- because the auth path does a direct index lookup by hash rather than a
-- per-row secret comparison (see InputProcessor.resolveTenantApp). Apps no
-- longer own a chart of accounts — see `accounts` below.
CREATE TABLE apps (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    app_name VARCHAR(255) NOT NULL,
    api_key_live_hash CHAR(64) UNIQUE,
    api_key_test_hash CHAR(64) UNIQUE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- 3. Chart of Accounts — ONE table, shared by the whole organization.
-- account_code is globally unique (not per-app as in v1): every App posts
-- entries against the same set of accounts. Only console Admins can create
-- or deactivate accounts (Accounts.java enforces the ADMIN role check);
-- Apps may still read the list via their API key to validate codes.
CREATE TABLE accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_code VARCHAR(32) NOT NULL UNIQUE,
    account_name VARCHAR(128) NOT NULL,
    account_type VARCHAR(16) NOT NULL CHECK (account_type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by_user_id UUID REFERENCES operators(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- 4. Journal Entries — the hash-chained block record (FSD Section 5 / TDD
-- Section 5): entry_payload_hash = P_n, previous_block_hash = H_(n-1),
-- block_hash = H_n. sequence_number/the hash chain remain PER app_id (each
-- App's own stream of submissions is independently tamper-evident) — moving
-- the Chart of Accounts to a single global table does not change this; a
-- reversal is simply the next entry in the same App's chain.
--
-- Concurrency safety: post_entry/reverse_entry/approve_reversal must
-- `SELECT ... FOR UPDATE` the parent apps row before computing the next
-- sequence_number/block_hash, inside the same transaction as this insert —
-- otherwise two concurrent posts for the same app can race and either
-- duplicate a sequence_number or branch the hash chain. Different apps post
-- concurrently without contention since they lock different apps rows.
--
-- idempotency_key persists the mandatory Idempotency-Key header so a retried
-- POST can be detected and answered from the original row instead of
-- double-posting (FSD Section 4, item 3).
--
-- entry_type/reverses_entry_id support reversal (see reversal_requests):
-- a REVERSAL entry is a normal, immutable, hash-chained journal_entries row
-- like any other — nothing is ever mutated or deleted, a reversal only adds
-- a new offsetting entry. posted_by_user_id is set only when a console user
-- posted/approved the entry manually (a manual entry, or the entry posted on
-- reversal approval); it's NULL for App-originated entries, whose provenance
-- is already app_id + the app's own API key.
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
    entry_type VARCHAR(10) NOT NULL DEFAULT 'STANDARD' CHECK (entry_type IN ('STANDARD', 'REVERSAL')),
    reverses_entry_id UUID REFERENCES journal_entries(id),
    posted_by_user_id UUID REFERENCES operators(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_app_sequence UNIQUE (app_id, sequence_number),
    CONSTRAINT uq_app_idempotency_key UNIQUE (app_id, idempotency_key)
);
CREATE INDEX idx_entries_audit_chain ON journal_entries(app_id, sequence_number DESC);
CREATE INDEX idx_entries_posted_at ON journal_entries(posted_at);
CREATE INDEX idx_entries_reverses ON journal_entries(reverses_entry_id);

-- 5. Entry Lines — individual debit/credit legs of a journal entry. The
-- double-entry invariant (sum(DEBIT) = sum(CREDIT) per entry_id) is enforced
-- in application code at post time, not by a DB constraint, since Postgres
-- has no cross-row CHECK.
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

-- 6. Reversal Requests — maker-checker workflow for console-initiated
-- reversals (Ledger.request_reversal / approve_reversal / reject_reversal).
-- An App reversing its OWN entry via its API key (Ledger.reverse_entry)
-- posts immediately and never creates a row here — maker-checker only
-- applies to manual, console-initiated reversals, where there's no other
-- system-of-record validating the correction.
--
-- The partial unique index below prevents a second reversal request while
-- one is still PENDING or has already been APPROVED for the same original
-- entry — an entry can only ever be reversed once.
CREATE TABLE reversal_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    original_entry_id UUID NOT NULL REFERENCES journal_entries(id),
    requested_by_user_id UUID NOT NULL REFERENCES operators(id),
    reason TEXT NOT NULL,
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    approved_by_user_id UUID REFERENCES operators(id),
    decision_reason TEXT,
    resulting_entry_id UUID REFERENCES journal_entries(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    decided_at TIMESTAMP WITH TIME ZONE
);
CREATE UNIQUE INDEX uq_reversal_requests_open ON reversal_requests(original_entry_id) WHERE status IN ('PENDING', 'APPROVED');
CREATE INDEX idx_reversal_requests_status ON reversal_requests(status);
