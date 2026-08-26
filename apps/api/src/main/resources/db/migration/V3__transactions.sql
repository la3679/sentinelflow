-- V3 - the transaction ledger this system exists to assess.
--
-- Two things in this table are load-bearing beyond the obvious.
--
-- The unique constraint on (account_id, idempotency_key) is the idempotency
-- guarantee itself. Ingestion is at-least-once by design (ADR-0006), so a
-- retried submission is normal traffic; the application returns the original
-- result, and this constraint is what makes that true even when two retries
-- race in different threads or different instances. Application code cannot
-- provide that: a check-then-insert has a window between the two statements.
--
-- occurred_at and ingested_at are separate facts and both are kept. A replayed
-- scenario occurred whenever the scenario says it did and was ingested now.
-- Collapsing them makes every replayed transaction look like it happened at
-- import time, which destroys every velocity feature computed from it.

CREATE TABLE transactions (
    id                    uuid          PRIMARY KEY DEFAULT uuidv7(),
    transaction_reference varchar(16)   NOT NULL,
    idempotency_key       varchar(128)  NOT NULL,

    account_id            uuid          NOT NULL,
    merchant_id           uuid          NOT NULL,

    type                  varchar(16)   NOT NULL,
    channel               varchar(24)   NOT NULL,

    amount                numeric(19,4) NOT NULL,
    currency              varchar(3)    NOT NULL,

    origin_country        varchar(2)    NOT NULL,
    device_reference      varchar(16),

    occurred_at           timestamptz   NOT NULL,
    ingested_at           timestamptz   NOT NULL DEFAULT now(),
    ingestion_source      varchar(16)   NOT NULL,
    processing_status     varchar(16)   NOT NULL,

    correlation_id        uuid          NOT NULL,
    version               bigint        NOT NULL DEFAULT 0,

    created_at            timestamptz   NOT NULL DEFAULT now(),
    updated_at            timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT transactions_reference_unique UNIQUE (transaction_reference),
    CONSTRAINT transactions_reference_format CHECK (transaction_reference ~ '^TXN-[0-9]{6}$'),

    -- Per account, not global. Two different clients submitting the same key
    -- for two different accounts are not duplicates of each other.
    CONSTRAINT transactions_idempotency_unique UNIQUE (account_id, idempotency_key),
    CONSTRAINT transactions_idempotency_length CHECK (char_length(idempotency_key) BETWEEN 8 AND 128),

    CONSTRAINT transactions_type_known
        CHECK (type IN ('PURCHASE', 'REFUND', 'TRANSFER', 'WITHDRAWAL', 'DEPOSIT')),
    CONSTRAINT transactions_channel_known
        CHECK (channel IN ('CARD_PRESENT', 'CARD_NOT_PRESENT', 'ONLINE_TRANSFER', 'ATM', 'DIRECT_DEBIT')),
    CONSTRAINT transactions_ingestion_source_known
        CHECK (ingestion_source IN ('API', 'BATCH', 'GENERATOR', 'SCENARIO_REPLAY')),
    CONSTRAINT transactions_processing_status_known
        CHECK (processing_status IN ('PENDING', 'ASSESSED', 'FAILED')),

    -- Direction is carried by `type`, so a zero-value movement is meaningless
    -- rather than merely unusual. Sign is left legal because the contract's
    -- money type permits it and a reversal may legitimately be negative.
    CONSTRAINT transactions_amount_nonzero CHECK (amount <> 0),
    CONSTRAINT transactions_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT transactions_origin_country_format CHECK (origin_country ~ '^[A-Z]{2}$'),
    -- Null means "this channel has no device", which is a real answer. A
    -- malformed device handle is not.
    CONSTRAINT transactions_device_format CHECK (device_reference IS NULL OR device_reference ~ '^DEV-[0-9a-f]{12}$'),

    CONSTRAINT transactions_account_fk FOREIGN KEY (account_id) REFERENCES accounts (id) ON DELETE RESTRICT,
    CONSTRAINT transactions_merchant_fk FOREIGN KEY (merchant_id) REFERENCES merchants (id) ON DELETE RESTRICT
);

COMMENT ON TABLE transactions IS
    'Synthetic transactions. No real cardholder, account or payment data is represented here.';

-- Every velocity feature and every account timeline reads one account's
-- transactions newest-first. This index is the reason those are not sequential
-- scans, and it is the only index here that was not implied by a constraint.
CREATE INDEX transactions_account_occurred_idx ON transactions (account_id, occurred_at DESC);

-- Partial, because the scan that looks for work to do only ever asks for
-- PENDING rows, and an index over the ASSESSED majority would be almost
-- entirely dead weight on every write.
CREATE INDEX transactions_pending_idx ON transactions (occurred_at)
    WHERE processing_status = 'PENDING';
