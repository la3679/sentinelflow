-- V4 - scoring output and the human workflow it opens.
--
-- The constraints here encode the two invariants that are easiest to break in
-- application code and hardest to notice afterwards:
--
--   1. A degraded assessment is one produced without the model. It has no model
--      score, no model version, and no scoring latency, because no scoring call
--      happened. Any other combination is a bug that would otherwise be
--      published to Kafka, stored, and later read as a real model output.
--   2. An alert transition records where it came from. A transition row with a
--      null previous status is an audit trail that cannot answer the question
--      audits ask.

CREATE TABLE risk_assessments (
    id                 uuid         PRIMARY KEY DEFAULT uuidv7(),
    transaction_id     uuid         NOT NULL,
    -- Rescoring a transaction under a new policy produces a new assessment
    -- rather than overwriting the old one; the old decision is what was acted
    -- on and stays readable. Uniqueness is per version, not per transaction.
    assessment_version integer      NOT NULL DEFAULT 1,

    rule_score         numeric(5,2) NOT NULL,
    model_score        numeric(5,2),
    final_score        numeric(5,2) NOT NULL,
    risk_band          varchar(16)  NOT NULL,
    degraded           boolean      NOT NULL,

    model_version      varchar(32),
    feature_version    varchar(32),
    policy_version     varchar(32)  NOT NULL,

    -- JSONB is correct here and almost nowhere else in this schema: a reason
    -- code list is genuinely variable in length and shape, and nothing queries
    -- or constrains an individual member. The domain does not live in here.
    reason_codes       jsonb        NOT NULL,

    scoring_latency_ms integer      NOT NULL,
    alert_raised       boolean      NOT NULL,
    assessed_at        timestamptz  NOT NULL,
    created_at         timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT risk_assessments_version_unique UNIQUE (transaction_id, assessment_version),
    CONSTRAINT risk_assessments_version_positive CHECK (assessment_version >= 1),

    CONSTRAINT risk_assessments_rule_score_range CHECK (rule_score BETWEEN 0 AND 100),
    CONSTRAINT risk_assessments_model_score_range CHECK (model_score IS NULL OR model_score BETWEEN 0 AND 100),
    CONSTRAINT risk_assessments_final_score_range CHECK (final_score BETWEEN 0 AND 100),
    CONSTRAINT risk_assessments_band_known CHECK (risk_band IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),

    -- Semantic versions, compared as opaque strings rather than parsed.
    CONSTRAINT risk_assessments_policy_version_format
        CHECK (policy_version ~ '^[0-9]+\.[0-9]+\.[0-9]+$'),
    CONSTRAINT risk_assessments_model_version_format
        CHECK (model_version IS NULL OR model_version ~ '^[0-9]+\.[0-9]+\.[0-9]+$'),
    CONSTRAINT risk_assessments_feature_version_format
        CHECK (feature_version IS NULL OR feature_version ~ '^[0-9]+\.[0-9]+\.[0-9]+$'),

    -- Degraded and not degraded are each fully specified. There is no third
    -- shape, and in particular there is no "degraded but here is a model score
    -- anyway" row, which is what a partially-failed scoring path produces.
    CONSTRAINT risk_assessments_degraded_consistent CHECK (
        (degraded AND model_score IS NULL AND model_version IS NULL AND feature_version IS NULL
             AND scoring_latency_ms = 0)
        OR
        (NOT degraded AND model_score IS NOT NULL AND model_version IS NOT NULL
             AND feature_version IS NOT NULL)
    ),
    CONSTRAINT risk_assessments_latency_nonnegative CHECK (scoring_latency_ms >= 0),

    -- An explanation an analyst cannot read is not an explanation, and an
    -- assessment with no reason at all cannot be defended to anyone.
    CONSTRAINT risk_assessments_reason_codes_shape CHECK (
        jsonb_typeof(reason_codes) = 'array'
        AND jsonb_array_length(reason_codes) BETWEEN 1 AND 20
    ),

    CONSTRAINT risk_assessments_transaction_fk
        FOREIGN KEY (transaction_id) REFERENCES transactions (id) ON DELETE RESTRICT
);

-- "The current assessment for this transaction" is the read behind every
-- transaction detail view.
CREATE INDEX risk_assessments_transaction_idx
    ON risk_assessments (transaction_id, assessment_version DESC);

CREATE TABLE alerts (
    id              uuid         PRIMARY KEY DEFAULT uuidv7(),
    alert_reference varchar(16)  NOT NULL,
    transaction_id  uuid         NOT NULL,
    assessment_id   uuid         NOT NULL,

    status          varchar(32)  NOT NULL,
    priority        varchar(16)  NOT NULL,
    assignee_id     uuid,
    summary         varchar(500) NOT NULL,
    risk_band       varchar(16)  NOT NULL,
    final_score     numeric(5,2) NOT NULL,

    -- Optimistic lock. Two analysts opening the same alert and both acting is
    -- the normal case in a queue, not an edge case, and the loser of that race
    -- must be told rather than silently overwritten.
    version         bigint       NOT NULL DEFAULT 0,

    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    closed_at       timestamptz,

    CONSTRAINT alerts_reference_unique UNIQUE (alert_reference),
    CONSTRAINT alerts_reference_format CHECK (alert_reference ~ '^ALT-[0-9]{4}$'),

    -- One alert per assessment. Retrying the alert-raising path after a
    -- partial failure must not open a second alert for the same decision.
    CONSTRAINT alerts_assessment_unique UNIQUE (assessment_id),

    CONSTRAINT alerts_status_known CHECK (status IN (
        'NEW', 'IN_REVIEW', 'ESCALATED', 'CONFIRMED_SUSPICIOUS', 'DISMISSED_FALSE_POSITIVE', 'CLOSED')),
    CONSTRAINT alerts_priority_known CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT')),
    CONSTRAINT alerts_band_known CHECK (risk_band IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT alerts_final_score_range CHECK (final_score BETWEEN 0 AND 100),
    CONSTRAINT alerts_summary_not_blank CHECK (char_length(btrim(summary)) > 0),
    CONSTRAINT alerts_version_nonnegative CHECK (version >= 0),

    -- A terminal alert has a close time and a live one does not. Without this,
    -- "how long did this take to resolve" silently becomes unanswerable for
    -- whichever rows the application forgot to stamp.
    CONSTRAINT alerts_closed_at_consistent CHECK (
        (status IN ('CONFIRMED_SUSPICIOUS', 'DISMISSED_FALSE_POSITIVE', 'CLOSED') AND closed_at IS NOT NULL)
        OR
        (status IN ('NEW', 'IN_REVIEW', 'ESCALATED') AND closed_at IS NULL)
    ),

    CONSTRAINT alerts_transaction_fk
        FOREIGN KEY (transaction_id) REFERENCES transactions (id) ON DELETE RESTRICT,
    CONSTRAINT alerts_assessment_fk
        FOREIGN KEY (assessment_id) REFERENCES risk_assessments (id) ON DELETE RESTRICT,
    CONSTRAINT alerts_assignee_fk FOREIGN KEY (assignee_id) REFERENCES users (id) ON DELETE RESTRICT
);

-- The alert queue: open work, most urgent and oldest first. This is the single
-- most frequent read in the product.
CREATE INDEX alerts_queue_idx ON alerts (status, priority, created_at DESC);

-- What is on one analyst's desk. Partial, because that view never asks about
-- closed alerts and unassigned rows would otherwise dominate the index.
CREATE INDEX alerts_assignee_open_idx ON alerts (assignee_id, created_at DESC)
    WHERE assignee_id IS NOT NULL AND status IN ('NEW', 'IN_REVIEW', 'ESCALATED');

CREATE INDEX alerts_transaction_idx ON alerts (transaction_id);

CREATE TABLE alert_actions (
    id              uuid          PRIMARY KEY DEFAULT uuidv7(),
    alert_id        uuid          NOT NULL,
    -- NOT NULL, always. An automated action is attributed to the system
    -- principal V1 inserts, so an unattributable change to a reviewed decision
    -- is not representable rather than merely discouraged.
    actor_id        uuid          NOT NULL,
    actor_role      varchar(16)   NOT NULL,
    action_type     varchar(24)   NOT NULL,
    previous_status varchar(32),
    new_status      varchar(32),
    note            varchar(2000),
    correlation_id  uuid          NOT NULL,
    occurred_at     timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT alert_actions_role_known
        CHECK (actor_role IN ('ANALYST', 'ADMINISTRATOR', 'AUDITOR', 'SYSTEM')),
    CONSTRAINT alert_actions_type_known CHECK (action_type IN (
        'CREATED', 'ASSIGNED', 'UNASSIGNED', 'TRANSITIONED', 'NOTE_ADDED', 'PRIORITY_CHANGED')),
    CONSTRAINT alert_actions_previous_status_known CHECK (previous_status IS NULL OR previous_status IN (
        'NEW', 'IN_REVIEW', 'ESCALATED', 'CONFIRMED_SUSPICIOUS', 'DISMISSED_FALSE_POSITIVE', 'CLOSED')),
    CONSTRAINT alert_actions_new_status_known CHECK (new_status IS NULL OR new_status IN (
        'NEW', 'IN_REVIEW', 'ESCALATED', 'CONFIRMED_SUSPICIOUS', 'DISMISSED_FALSE_POSITIVE', 'CLOSED')),

    -- A transition that does not say what it moved from and to is not a record
    -- of a transition.
    CONSTRAINT alert_actions_transition_complete CHECK (
        action_type <> 'TRANSITIONED'
        OR (previous_status IS NOT NULL AND new_status IS NOT NULL AND previous_status <> new_status)
    ),
    CONSTRAINT alert_actions_note_present CHECK (
        action_type <> 'NOTE_ADDED' OR char_length(btrim(coalesce(note, ''))) > 0
    ),

    CONSTRAINT alert_actions_alert_fk FOREIGN KEY (alert_id) REFERENCES alerts (id) ON DELETE RESTRICT,
    CONSTRAINT alert_actions_actor_fk FOREIGN KEY (actor_id) REFERENCES users (id) ON DELETE RESTRICT
);

COMMENT ON TABLE alert_actions IS
    'Append-only history of everything done to an alert. Never updated, never deleted.';

-- One alert's history in order: the investigation timeline.
CREATE INDEX alert_actions_alert_occurred_idx ON alert_actions (alert_id, occurred_at);

CREATE TABLE analyst_feedback (
    id            uuid          PRIMARY KEY DEFAULT uuidv7(),
    assessment_id uuid          NOT NULL,
    alert_id      uuid,
    actor_id      uuid          NOT NULL,
    label         varchar(24)   NOT NULL,
    reason        varchar(1000),
    created_at    timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT analyst_feedback_label_known
        CHECK (label IN ('TRUE_POSITIVE', 'FALSE_POSITIVE', 'INCONCLUSIVE')),
    -- One analyst, one label per assessment. Changing your mind updates the
    -- row; it does not add a second contradictory training label.
    CONSTRAINT analyst_feedback_unique UNIQUE (assessment_id, actor_id),

    CONSTRAINT analyst_feedback_assessment_fk
        FOREIGN KEY (assessment_id) REFERENCES risk_assessments (id) ON DELETE RESTRICT,
    CONSTRAINT analyst_feedback_alert_fk FOREIGN KEY (alert_id) REFERENCES alerts (id) ON DELETE RESTRICT,
    CONSTRAINT analyst_feedback_actor_fk FOREIGN KEY (actor_id) REFERENCES users (id) ON DELETE RESTRICT
);

COMMENT ON TABLE analyst_feedback IS
    'Analyst dispositions, the label source for future supervised training.';
