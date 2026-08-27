"""Turning a linear model's own arithmetic into reason codes.

**What a contribution here is.** The served model is a scaler followed by a
calibrated logistic regression. A logistic regression's decision is a sum of
``coefficient x standardised value`` over the columns, so a per-feature
contribution is not an approximation of the model — it *is* the model, taken
apart. That is the property ADR-0010 §5 selected logistic regression for, over a
tree ensemble that scored no better than fold noise.

**What it is not.** The contribution is on the log-odds scale *before*
calibration, and ``modelScore`` is the calibrated probability rescaled to 0 to 100.
Calibration is monotone, so the contributions explain the **ranking** — why this
transaction sits where it does — and they do not sum to the score. The contract
says as much in fewer words: "Units are the model's own and are only comparable
within one ``modelVersion``." Presenting them as additive parts of the 0-to-100
number would be a lie an analyst could not detect.

**A model that cannot be taken apart returns no reasons.** If a future model is
not a linear pipeline, :func:`contributions` returns ``None`` and the response
carries an empty ``reasons`` list and a warning saying why. An invented
explanation is worse than an absent one, because only one of the two is visibly
missing.

**Boolean features are only ever a reason when they fired.** ``is_new_device`` at
0.0 still has a standardised value and therefore a non-zero contribution, and
emitting ``NEW_DEVICE`` for a transaction on a device the account has always used
would be an explanation that says the opposite of what happened. Continuous
features carry a direction instead, taken from the standardised value: above the
training mean is ``_HIGH``, below it is ``_LOW``.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np
from numpy.typing import NDArray

from sentinelflow_scoring.features import FEATURE_NAMES
from sentinelflow_scoring.serving.schema import MAX_REASONS, ReasonContribution


@dataclass(frozen=True, slots=True)
class ReasonDefinition:
    """How one feature is named to an analyst.

    :param stem: the stable part of the code. **Never renamed once emitted** — a
        renamed code silently breaks every historical query, and the console
        groups and filters on it.
    :param boolean: whether the feature is an indicator. Indicators are reported
        only when they fired and carry no direction suffix; everything else
        carries ``_HIGH`` or ``_LOW``.
    """

    stem: str
    boolean: bool


#: One entry per column in :data:`sentinelflow_scoring.features.FEATURE_NAMES`.
#: The mapping is deliberately one-to-one: ``amount`` and ``log_amount`` are two
#: views of one quantity and could be reported as a single reason, but then a
#: contribution would no longer be attributable to a column, which is the whole
#: reason it is defensible to show one.
DEFINITIONS: dict[str, ReasonDefinition] = {
    "account_age_days": ReasonDefinition("ACCOUNT_AGE", boolean=False),
    "amount": ReasonDefinition("AMOUNT", boolean=False),
    "amount_to_account_mean_ratio": ReasonDefinition("AMOUNT_RATIO", boolean=False),
    "balance_drain_ratio": ReasonDefinition("BALANCE_DRAIN", boolean=False),
    "count_1m": ReasonDefinition("VELOCITY_1M", boolean=False),
    "count_5m": ReasonDefinition("VELOCITY_5M", boolean=False),
    "count_60m": ReasonDefinition("VELOCITY_60M", boolean=False),
    "distinct_merchants_1h": ReasonDefinition("DISTINCT_MERCHANTS_1H", boolean=False),
    "history_size": ReasonDefinition("HISTORY_SIZE", boolean=False),
    "is_channel_change": ReasonDefinition("CHANNEL_CHANGE", boolean=True),
    "is_country_change": ReasonDefinition("COUNTRY_CHANGE", boolean=True),
    "is_new_device": ReasonDefinition("NEW_DEVICE", boolean=True),
    "is_new_merchant": ReasonDefinition("NEW_MERCHANT", boolean=True),
    "is_off_hours": ReasonDefinition("OFF_HOURS", boolean=True),
    "is_rounded_amount": ReasonDefinition("ROUNDED_AMOUNT", boolean=True),
    "log_amount": ReasonDefinition("LOG_AMOUNT", boolean=False),
    "seconds_since_previous": ReasonDefinition("TIME_SINCE_PREVIOUS", boolean=False),
    "sum_1h": ReasonDefinition("SPEND_1H", boolean=False),
    "sum_24h": ReasonDefinition("SPEND_24H", boolean=False),
}

#: Reported when the active model cannot be decomposed. Under 200 characters,
#: which is the contract's cap on a warning.
NO_ATTRIBUTION_WARNING = (
    "the active model does not expose per-feature contributions; reasons are empty for this "
    "model version"
)


@dataclass(frozen=True, slots=True)
class Attribution:
    """A linear model taken apart for one request.

    :param standardised: each column's value in units of training standard
        deviations. The sign is what makes ``_HIGH`` and ``_LOW`` mean something
        rather than being a guess about a threshold nobody set.
    :param contribution: ``coefficient x standardised``, on the log-odds scale.
    """

    standardised: dict[str, float]
    contribution: dict[str, float]


def contributions(estimator: object, vector: NDArray[np.float64]) -> Attribution | None:
    """Decompose a linear pipeline's decision for one row.

    Returns ``None`` — not an exception and not an empty result — when the
    estimator is not a shape this can decompose. The difference matters: ``None``
    is reported to the caller as a warning, where an empty attribution would be
    indistinguishable from a model that genuinely found nothing.

    :param vector: one row, already in :data:`FEATURE_NAMES` order.
    """
    steps = getattr(estimator, "steps", None)
    if not steps:
        return None

    final = steps[-1][1]
    coefficients = _mean_coefficients(final)
    if coefficients is None:
        return None

    # Everything before the estimator, which for this pipeline is the scaler.
    # Taken from the pipeline rather than reconstructed, so a step added between
    # the two is included instead of silently ignored.
    transformed = np.asarray(estimator[:-1].transform(vector), dtype=np.float64)  # type: ignore[index]
    standardised = transformed[0]
    if standardised.shape != coefficients.shape:
        return None

    return Attribution(
        standardised={name: float(standardised[index]) for index, name in enumerate(FEATURE_NAMES)},
        contribution={
            name: float(coefficients[index] * standardised[index])
            for index, name in enumerate(FEATURE_NAMES)
        },
    )


def build(attribution: Attribution, features: dict[str, float]) -> list[ReasonContribution]:
    """The bounded, most-significant-first reason list the contract asks for.

    Ordered by absolute contribution, because a factor that pushed the score
    *down* by a lot is as much of an explanation as one that pushed it up, and the
    sign is on the wire for a caller that wants to tell them apart. Ties break on
    the code, so two runs of the same request produce the same list — the contract
    calls this endpoint idempotent and a response whose order moved would make a
    liar of it.
    """
    scored: list[tuple[float, str, float]] = []
    for name, value in attribution.contribution.items():
        if value == 0.0:
            # Not a reason. A zero contribution is the model saying this column
            # made no difference, and listing it would spend one of ten slots
            # saying nothing.
            continue
        definition = DEFINITIONS[name]
        if definition.boolean:
            if features[name] != 1.0:
                continue
            code = definition.stem
        else:
            code = (
                f"{definition.stem}_HIGH"
                if attribution.standardised[name] > 0
                else (f"{definition.stem}_LOW")
            )
        scored.append((abs(value), code, value))

    scored.sort(key=lambda item: (-item[0], item[1]))
    return [
        ReasonContribution(code=code, contribution=value) for _, code, value in scored[:MAX_REASONS]
    ]


def _mean_coefficients(final: object) -> NDArray[np.float64] | None:
    """The coefficients of a fitted linear estimator, or of a calibrated ensemble.

    ``CalibratedClassifierCV`` with an integer ``cv`` fits one base estimator per
    fold and averages their probabilities, so the honest single set of
    coefficients is the mean across those folds. Averaging is exact for the
    log-odds each fold contributes and approximate for the ensemble's averaged
    probability — the approximation is in the same direction as calibration
    itself, and neither changes the ordering the reasons explain.
    """
    calibrated = getattr(final, "calibrated_classifiers_", None)
    if calibrated:
        inner = [getattr(member, "estimator", None) for member in calibrated]
        matrices = [_coefficients_of(estimator) for estimator in inner]
        if any(matrix is None for matrix in matrices):
            return None
        return np.mean(np.vstack([matrix for matrix in matrices if matrix is not None]), axis=0)
    return _coefficients_of(final)


def _coefficients_of(estimator: object) -> NDArray[np.float64] | None:
    """``coef_`` for a fitted binary linear model, or ``None``.

    Binary only. A multi-class ``coef_`` has one row per class and there is no
    single "the contribution" to report; this service has two classes by
    construction, so more than one row means the estimator is not what it is
    assumed to be.
    """
    coefficients = getattr(estimator, "coef_", None)
    if coefficients is None:
        return None
    matrix = np.asarray(coefficients, dtype=np.float64)
    if matrix.ndim != 2 or matrix.shape[0] != 1:
        return None
    row: NDArray[np.float64] = matrix[0]
    return row
