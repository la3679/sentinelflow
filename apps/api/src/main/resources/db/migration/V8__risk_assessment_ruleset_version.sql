-- V8 - the version the rule half of a score was produced by.
--
-- V4 gave risk_assessments a model_version, a feature_version and a
-- policy_version, and no column for the ruleset. That was not noticed for the
-- same reason the reason_codes shape was not: nothing had ever written a row,
-- so no code had yet been forced to put every version it depends on somewhere.
-- Writing the assessment workflow is what forced it.
--
-- The gap matters because the rule score is not a smaller model score, it is a
-- different kind of number. Its contribution is exactly the weight configuration
-- says it is, so an analyst defending a decision can add the reasons up and get
-- the score - but only against the weights and thresholds that were in force.
-- Those move on their own schedule: sentinelflow.risk.rules.version is a
-- separate value from the policy version precisely because a threshold can be
-- retuned without the banding changing, and either can change without the model
-- moving at all. An assessment naming only the other three cannot be reproduced.
--
-- NOT NULL with no default and no backfill. There is nothing to backfill: no
-- released code path has ever inserted into this table, and the one that will is
-- in the same change as this migration. A default would be a fabricated version
-- attached to rows nobody can attribute, which is worse than the absence it
-- would hide - so the guard below refuses loudly instead of guessing.

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM risk_assessments) THEN
        RAISE EXCEPTION
            'risk_assessments already holds rows, which no released code path can have written. '
            'Adding ruleset_version NOT NULL would need a version for them, and inventing one '
            'would attribute a score to a ruleset that may not have produced it. Establish where '
            'those rows came from before migrating.';
    END IF;
END $$;

ALTER TABLE risk_assessments
    ADD COLUMN ruleset_version varchar(32) NOT NULL;

-- The same opaque-string comparison the other three versions get. Semantic
-- versions are compared as strings rather than parsed, so this constrains the
-- shape and nothing else.
ALTER TABLE risk_assessments
    ADD CONSTRAINT risk_assessments_ruleset_version_format
        CHECK (ruleset_version ~ '^[0-9]+\.[0-9]+\.[0-9]+$');

-- Not nullable even on a degraded assessment, and that is the point of putting
-- it here rather than beside model_version. A degraded assessment has no model
-- score and no model version because no scoring call happened; it always has a
-- rule score, because the ruleset is what runs in-process and answers when the
-- scoring service cannot (ADR-0008 section 3). The one number a degraded
-- assessment is made of is the one that had no version until now.
COMMENT ON COLUMN risk_assessments.ruleset_version IS
    'The sentinelflow.risk.rules version that produced rule_score and the RULE reason codes. '
    'Always present, including on a degraded assessment, which is scored by rules alone.';
