"""The rule that turns contributions into codes, tested without a model.

Through HTTP a reason list is capped at ten, so "this code is absent" can always
mean "it did not rank" rather than "the rule excluded it". These use a synthetic
attribution so each clause is asserted on its own.
"""

from __future__ import annotations

from sentinelflow_scoring.features import FEATURE_NAMES
from sentinelflow_scoring.serving import reasons
from sentinelflow_scoring.serving.schema import MAX_REASONS


def attribution(**contributions: float) -> reasons.Attribution:
    """An attribution over the real column list, zero except where named.

    ``standardised`` is given the same sign as the contribution, which is the
    ordinary case for a positive coefficient and is all these tests need; the
    direction suffix has its own test with the sign set deliberately.
    """
    values = {name: contributions.get(name, 0.0) for name in FEATURE_NAMES}
    return reasons.Attribution(standardised=dict(values), contribution=dict(values))


def features(**overrides: float) -> dict[str, float]:
    return {name: overrides.get(name, 0.0) for name in FEATURE_NAMES}


def test_every_feature_has_a_code() -> None:
    """A column with no definition would raise on the request path, and it would
    raise on whichever request first happened to give it a non-zero contribution
    — long after the change that added it."""
    assert set(reasons.DEFINITIONS) == set(FEATURE_NAMES)


def test_codes_are_unique() -> None:
    """Two features sharing a stem would produce two identical codes in one
    response, and an analyst filtering on that code would get both."""
    stems = [definition.stem for definition in reasons.DEFINITIONS.values()]

    assert len(set(stems)) == len(stems)


def test_an_indicator_that_fired_is_reported() -> None:
    built = reasons.build(
        attribution(is_new_device=2.0),
        features(is_new_device=1.0),
    )

    assert [reason.code for reason in built] == ["NEW_DEVICE"]


def test_an_indicator_that_did_not_fire_is_not_reported() -> None:
    """It still has a standardised value and a non-zero contribution. Reporting
    ``NEW_DEVICE`` for a device the account has always used would be an
    explanation that says the opposite of what happened."""
    built = reasons.build(
        attribution(is_new_device=2.0),
        features(is_new_device=0.0),
    )

    assert built == []


def test_an_indicator_carries_no_direction_suffix() -> None:
    """``NEW_DEVICE_HIGH`` is not a thing an indicator can be."""
    built = reasons.build(attribution(is_off_hours=1.0), features(is_off_hours=1.0))

    assert built[0].code == "OFF_HOURS"


def test_a_continuous_feature_above_the_training_mean_is_high() -> None:
    built = reasons.build(
        reasons.Attribution(
            standardised={name: 3.0 if name == "count_1m" else 0.0 for name in FEATURE_NAMES},
            contribution={name: 1.5 if name == "count_1m" else 0.0 for name in FEATURE_NAMES},
        ),
        features(),
    )

    assert built[0].code == "VELOCITY_1M_HIGH"


def test_a_continuous_feature_below_the_training_mean_is_low() -> None:
    """The direction describes the feature, not the contribution.

    A below-average value with a negative coefficient contributes *upwards*, and
    calling that ``_HIGH`` would tell an analyst the opposite of what the data
    said.
    """
    built = reasons.build(
        reasons.Attribution(
            standardised={name: -3.0 if name == "count_1m" else 0.0 for name in FEATURE_NAMES},
            contribution={name: 1.5 if name == "count_1m" else 0.0 for name in FEATURE_NAMES},
        ),
        features(),
    )

    assert built[0].code == "VELOCITY_1M_LOW"


def test_a_zero_contribution_is_not_a_reason() -> None:
    """The model saying a column made no difference is not an explanation, and
    listing it would spend one of ten slots saying nothing."""
    assert reasons.build(attribution(), features()) == []


def test_the_list_is_ordered_by_magnitude_and_capped() -> None:
    """A factor that pushed the score down by a lot explains as much as one that
    pushed it up, so the order is by absolute size and the sign is on the wire."""
    built = reasons.build(
        attribution(
            count_1m=1.0,
            count_5m=-9.0,
            count_60m=5.0,
            sum_1h=-2.0,
            sum_24h=0.5,
            amount=3.0,
            log_amount=-4.0,
            account_age_days=0.25,
            balance_drain_ratio=0.75,
            distinct_merchants_1h=6.0,
            history_size=7.0,
            seconds_since_previous=8.0,
        ),
        features(),
    )

    assert len(built) == MAX_REASONS
    magnitudes = [abs(reason.contribution) for reason in built]
    assert magnitudes == sorted(magnitudes, reverse=True)
    assert built[0].contribution == -9.0


def test_ties_break_deterministically() -> None:
    """Two identical requests must produce two identical responses, and a list
    whose order moved between them would make a liar of the contract's
    idempotency claim."""
    equal = attribution(count_1m=2.0, count_5m=2.0, count_60m=2.0)

    first = reasons.build(equal, features())
    second = reasons.build(equal, features())

    assert [reason.code for reason in first] == [reason.code for reason in second]
    assert [reason.code for reason in first] == sorted(reason.code for reason in first)


def test_an_estimator_with_no_coefficients_cannot_be_decomposed() -> None:
    """Reported as ``None`` rather than as an empty attribution: the caller is
    told the model cannot be explained instead of being told it found nothing."""
    import numpy as np

    assert reasons.contributions(object(), np.zeros((1, len(FEATURE_NAMES)))) is None


def test_a_calibrated_ensemble_of_non_linear_models_cannot_be_decomposed() -> None:
    """The whole ensemble is refused, not averaged over the members that happen to
    have coefficients. A mean over a subset would be an attribution of part of a
    model presented as an attribution of the model."""
    import numpy as np
    from sklearn.linear_model import LogisticRegression
    from sklearn.pipeline import Pipeline
    from sklearn.preprocessing import StandardScaler

    columns = len(FEATURE_NAMES)
    linear = LogisticRegression(max_iter=1000)
    linear.fit([[1.0] * columns, [0.0] * columns], [1, 0])

    class _Member:
        def __init__(self, estimator: object) -> None:
            self.estimator = estimator

    class _Calibrated:
        calibrated_classifiers_ = (_Member(linear), _Member(object()))

    pipeline = Pipeline([("scale", StandardScaler()), ("model", _Calibrated())])
    pipeline.steps[0][1].fit([[1.0] * columns, [0.0] * columns])

    assert reasons.contributions(pipeline, np.ones((1, columns))) is None


def test_a_multiclass_coefficient_matrix_is_refused() -> None:
    """One row per class and no single "the contribution" to report. This service
    has two classes by construction, so more than one row means the estimator is
    not what it is assumed to be."""
    import numpy as np
    from sklearn.pipeline import Pipeline
    from sklearn.preprocessing import StandardScaler

    columns = len(FEATURE_NAMES)

    class _ThreeClass:
        coef_ = np.zeros((3, columns))

    pipeline = Pipeline([("scale", StandardScaler()), ("model", _ThreeClass())])
    pipeline.steps[0][1].fit([[1.0] * columns, [0.0] * columns])

    assert reasons.contributions(pipeline, np.ones((1, columns))) is None


def test_a_coefficient_count_that_does_not_match_the_columns_is_refused() -> None:
    """Zipping a shorter coefficient vector against the column list would attribute
    every contribution to the wrong feature, silently."""
    import numpy as np
    from sklearn.pipeline import Pipeline
    from sklearn.preprocessing import StandardScaler

    columns = len(FEATURE_NAMES)

    class _TooFew:
        coef_ = np.zeros((1, columns - 3))

    pipeline = Pipeline([("scale", StandardScaler()), ("model", _TooFew())])
    pipeline.steps[0][1].fit([[1.0] * columns, [0.0] * columns])

    assert reasons.contributions(pipeline, np.ones((1, columns))) is None
