"""The candidates ADR-0010 §2 fixed, and which of them may ship.

Four are compared. Three are eligible to be served; the fourth is a comparison
that answers a question worth asking and cannot honestly be a peer.

**Every one is calibrated, and that follows from ADR-0008 §4 rather than from
taste.** The API applies a single alerting threshold to a final score, and that
threshold must mean the same thing under any model and under a rules-only
degraded assessment. That is only coherent if the underlying quantity is on a
stable scale — so each supervised candidate is wrapped in
``CalibratedClassifierCV`` and its calibration is measured rather than assumed.

**The score on the wire is 0 to 100, not a probability.**
``contracts/openapi/sentinelflow-scoring.yaml`` fixes that and states it is not a
probability, because the positive class here is "belongs to a planted shape" and
not "is fraud". Calibration is a property of the mapping and not of the units, so
the two are not in tension: the calibrated value is carried onto the contract's
scale by a fixed monotone rescale, which leaves it calibrated.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass

import numpy as np
from numpy.typing import NDArray
from sklearn.calibration import CalibratedClassifierCV
from sklearn.ensemble import HistGradientBoostingClassifier, IsolationForest
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler

#: The contract's range for ``modelScore``. Higher is riskier.
SCORE_MIN = 0.0
SCORE_MAX = 100.0


@dataclass(frozen=True, slots=True)
class Candidate:
    """One model under comparison.

    :param name: stable, and written into the registry as part of the model
        version.
    :param eligible: whether it may be served. ``IsolationForest`` is not — see
        the module docstring and ADR-0010 §4.
    :param build: a factory rather than an instance, so every fold and the final
        fit start from an unfitted estimator. Reusing one instance across folds
        is a subtle leak: scikit-learn refits, but any state a caller set between
        folds would carry.
    """

    name: str
    eligible: bool
    build: Callable[[int], object]
    description: str


def _logistic(seed: int) -> Pipeline:
    """Explainable supervised baseline.

    Scaled, because the features span amounts in the thousands and boolean
    indicators in {0, 1}; without scaling the penalty falls almost entirely on the
    large-magnitude columns and the coefficients stop being comparable to each
    other — which is the one thing this candidate is chosen for.

    ``class_weight="balanced"`` because the positive class is a small minority and
    the unweighted fit converges on predicting the majority.
    """
    return Pipeline(
        [
            ("scale", StandardScaler()),
            (
                "model",
                CalibratedClassifierCV(
                    LogisticRegression(
                        max_iter=1000,
                        class_weight="balanced",
                        random_state=seed,
                    ),
                    method="sigmoid",
                    cv=3,
                ),
            ),
        ]
    )


def _boosted(seed: int) -> Pipeline:
    """Tree-based, and only shipped if it materially beats the baseline.

    No scaling: trees split on thresholds and are indifferent to monotone
    rescaling, so a scaler here would be machinery that changes nothing.

    ``isotonic`` rather than ``sigmoid``: a boosted model's raw scores are
    typically already sharp and mis-calibrated in a non-sigmoidal way, and
    isotonic can follow that shape. It needs more data than sigmoid to avoid
    overfitting the calibration itself, which is why the baseline does not use it.
    """
    return Pipeline(
        [
            (
                "model",
                CalibratedClassifierCV(
                    HistGradientBoostingClassifier(
                        max_iter=200,
                        learning_rate=0.1,
                        class_weight="balanced",
                        random_state=seed,
                    ),
                    method="isotonic",
                    cv=3,
                ),
            ),
        ]
    )


def _isolation_forest(seed: int) -> Pipeline:
    """Unsupervised comparison. Never served.

    It answers a question worth asking — how much of the planted structure is
    visible without labels at all — and it is excluded from candidacy because
    giving its unbounded, dataset-relative anomaly score a stable 0-to-100
    meaning requires calibrating against the labels it was included for being
    able to ignore.
    """
    return Pipeline(
        [
            ("scale", StandardScaler()),
            ("model", IsolationForest(n_estimators=200, random_state=seed, contamination="auto")),
        ]
    )


CANDIDATES: tuple[Candidate, ...] = (
    Candidate(
        name="logistic-regression",
        eligible=True,
        build=_logistic,
        description="Scaled, balanced logistic regression with sigmoid calibration.",
    ),
    Candidate(
        name="hist-gradient-boosting",
        eligible=True,
        build=_boosted,
        description="HistGradientBoostingClassifier with isotonic calibration.",
    ),
    Candidate(
        name="isolation-forest",
        eligible=False,
        build=_isolation_forest,
        description="Unsupervised anomaly comparison. Not eligible to ship (ADR-0010 §4).",
    ),
)


def positive_probability(estimator: object, x: NDArray[np.float64]) -> NDArray[np.float64]:
    """The probability-like quantity a candidate assigns to the positive class.

    Supervised candidates expose ``predict_proba``. ``IsolationForest`` does not
    and never will: ``score_samples`` returns an unbounded, dataset-relative
    quantity where lower is more anomalous. It is min-max mapped here **for
    reporting only**, and that mapping is exactly why the model cannot ship — it
    is relative to whichever rows happen to be in the array, so the same
    transaction scores differently depending on what it was scored alongside.
    """
    if hasattr(estimator, "predict_proba"):
        proba = estimator.predict_proba(x)
        return np.asarray(proba, dtype=np.float64)[:, 1]

    raw = -np.asarray(estimator.score_samples(x), dtype=np.float64)  # type: ignore[attr-defined]
    span = raw.max() - raw.min()
    if span == 0:
        # Every row equally anomalous. Constant 0.5 rather than a division by
        # zero or a silent nan that would propagate into every metric.
        return np.full_like(raw, 0.5)
    return (raw - raw.min()) / span


def to_contract_score(probability: NDArray[np.float64]) -> NDArray[np.float64]:
    """The calibrated quantity on the contract's 0-to-100 scale.

    A fixed monotone rescale, so a calibrated probability stays calibrated and a
    threshold expressed on this scale keeps meaning the same thing across model
    versions — which is the whole of ADR-0008 §4.
    """
    return SCORE_MIN + (SCORE_MAX - SCORE_MIN) * np.clip(probability, 0.0, 1.0)
