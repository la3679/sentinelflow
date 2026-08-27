-- V9 - the counter behind ALT-0001.
--
-- The same shape and the same reasoning as V7's transaction_reference_seq, and
-- for the same reason: alert_reference is the handle a person uses in a
-- conversation about an alert, it is unique and format-constrained, and
-- something has to allocate it without a read-modify-write race between two
-- assessments raising alerts at the same moment.
--
-- Not a DEFAULT on the column. The application assigns the value, because the
-- alert is built in memory alongside its first alert_actions row and its outbox
-- event, and all three are written together - a reference that only existed
-- after the INSERT would mean re-reading the row to build the event.
--
-- Gaps are expected, for the reason V7 records: sequences are not
-- transactional, so a rolled-back assessment consumes a value. The reference is
-- an identifier and not a count.
--
-- FOUR DIGITS CAPS THIS AT 9,999 ALERTS, and unlike the transaction reference's
-- six digits that is a ceiling this project can plausibly reach. The band
-- thresholds are a starting point rather than a measurement - docs/ml/EVALUATION.md
-- records that the score distribution is saturated, so almost nothing lands
-- between 40 and 90 - and an alerting rule tuned wrongly against a 20,000-row
-- demo dataset could exhaust this in one seeding.
--
-- That is deliberate and it is left as a loud failure rather than widened.
-- NO CYCLE means the ceiling arrives as a refused INSERT naming this sequence,
-- which is a legible signal that the alerting policy is producing more alerts
-- than any review capacity could absorb. Widening the format is a migration and
-- a contract change; making that decision now, before alert volume has been
-- measured against a stated capacity, would only move it somewhere less visible.

CREATE SEQUENCE alert_reference_seq
    AS bigint
    START WITH 1
    INCREMENT BY 1
    MINVALUE 1
    MAXVALUE 9999
    NO CYCLE;

COMMENT ON SEQUENCE alert_reference_seq IS
    'Allocates the numeric part of alerts.alert_reference. Gaps are expected and meaningless. '
    'Exhausting it is a signal that the alerting policy is too broad, not a reason to widen the format.';
