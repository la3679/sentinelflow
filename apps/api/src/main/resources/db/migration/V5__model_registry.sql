-- V5 - the record of which model produced which score.
--
-- A score without the exact configuration that produced it cannot be
-- reproduced, defended, or compared with another score. This table is what
-- makes the version strings on risk_assessments mean something: they name a row
-- here, with a training-data fingerprint and an artifact checksum, rather than
-- being free text nobody can resolve later.

CREATE TABLE model_registry (
    id                         uuid        PRIMARY KEY DEFAULT uuidv7(),
    model_version              varchar(32) NOT NULL,
    feature_version            varchar(32) NOT NULL,

    -- SHA-256 of the training set and of the serialised artifact. Together they
    -- answer "is the model running now the model these metrics describe", which
    -- is not a question a version string alone can answer.
    training_data_fingerprint  varchar(64) NOT NULL,
    artifact_checksum          varchar(64) NOT NULL,

    -- Evaluation results, whose keys vary as the evaluation does. Nothing
    -- queries an individual metric, so JSONB is the honest representation
    -- rather than a column per metric that goes stale.
    metrics                    jsonb       NOT NULL,

    status                     varchar(16) NOT NULL,
    trained_at                 timestamptz NOT NULL,
    promoted_at                timestamptz,
    created_at                 timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT model_registry_version_unique UNIQUE (model_version, feature_version),
    CONSTRAINT model_registry_model_version_format
        CHECK (model_version ~ '^[0-9]+\.[0-9]+\.[0-9]+$'),
    CONSTRAINT model_registry_feature_version_format
        CHECK (feature_version ~ '^[0-9]+\.[0-9]+\.[0-9]+$'),
    CONSTRAINT model_registry_fingerprint_format
        CHECK (training_data_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT model_registry_checksum_format CHECK (artifact_checksum ~ '^[0-9a-f]{64}$'),
    CONSTRAINT model_registry_status_known CHECK (status IN ('CANDIDATE', 'ACTIVE', 'RETIRED')),
    CONSTRAINT model_registry_metrics_object CHECK (jsonb_typeof(metrics) = 'object'),

    -- Promotion is what makes a model active, so an active model that was never
    -- promoted is a row that lost its own history.
    CONSTRAINT model_registry_promotion_consistent
        CHECK (status <> 'ACTIVE' OR promoted_at IS NOT NULL),
    CONSTRAINT model_registry_promotion_after_training
        CHECK (promoted_at IS NULL OR promoted_at >= trained_at)
);

-- At most one active model, enforced by the database rather than by whichever
-- code path last performed a promotion. A second active model is not a
-- degraded state to detect later; it makes "which model scored this" ambiguous
-- for every assessment written while it lasted.
CREATE UNIQUE INDEX model_registry_single_active_idx ON model_registry ((status))
    WHERE status = 'ACTIVE';

COMMENT ON TABLE model_registry IS
    'One row per trained model. Metrics are recorded from an evaluation run, never estimated.';
