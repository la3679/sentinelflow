-- V7 - the counter behind TXN-000001.
--
-- transaction_reference is the handle a person uses in a support conversation:
-- unique, format-constrained, and never a foreign key (ADR-0007). Something has
-- to allocate it, and a sequence is the only thing that can do so without a
-- read-modify-write race between two concurrent ingestions.
--
-- Not a DEFAULT on the column. The application assigns the value so that the
-- reference is known before the INSERT is attempted, which matters because the
-- idempotency constraint may reject that INSERT: a caller retrying gets the
-- original row's reference back, and the code that builds the response should
-- not have to re-read a row to find out what it was called.
--
-- Sequences are not transactional. A rolled-back ingestion consumes a value and
-- leaves a gap, and so does a rejected duplicate. That is correct and is worth
-- stating: the reference is an identifier, not a count, and nothing may infer
-- the number of transactions from the highest one issued.
--
-- Six digits caps this at 999,999 before the format constraint rejects the next
-- one. That is a demo-scale limit, deliberately: widening the format is a
-- migration and a contract change, and pretending otherwise now would only move
-- the decision somewhere less visible. NO CYCLE, so the ceiling arrives as a
-- loud failure rather than as a silent collision with TXN-000001.

CREATE SEQUENCE transaction_reference_seq
    AS bigint
    START WITH 1
    INCREMENT BY 1
    MINVALUE 1
    MAXVALUE 999999
    NO CYCLE;

COMMENT ON SEQUENCE transaction_reference_seq IS
    'Allocates the numeric part of transactions.transaction_reference. Gaps are expected and meaningless.';
