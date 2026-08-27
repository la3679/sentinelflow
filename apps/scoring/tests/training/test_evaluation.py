"""Metrics, and the things this project refuses to report.

The most important assertion here is a negative one: accuracy is not in the
metrics object. Under this imbalance it is the number that makes a useless model
look excellent, and a number that exists gets quoted.
"""

from __future__ import annotations

import numpy as np
import pytest

from sentinelflow_scoring.training.evaluation import (
    ALERT_BUDGET,
    evaluate,
    threshold_for_budget,
)


def test_accuracy_is_not_reported() -> None:
    y = np.array([0] * 99 + [1], dtype=np.int_)
    scores = np.zeros(100, dtype=np.float64)

    metrics = evaluate(y, scores).as_dict()

    # A model answering "not suspicious" to everything is 99% accurate here.
    # Leaving the number out of the object is what stops it being quoted.
    assert "accuracy" not in metrics
    assert metrics["pr_auc"] < 0.5


def test_the_threshold_matches_the_alert_budget() -> None:
    scores = np.linspace(0.0, 100.0, 1000)

    threshold = threshold_for_budget(scores, 0.01)
    alerted = (scores >= threshold).mean()

    assert alerted == pytest.approx(0.01, abs=0.002)


def test_the_realised_alert_rate_is_reported_not_forced() -> None:
    """Ties mean the budget cannot always be hit exactly.

    Half the scores identical, so the quantile lands on a plateau. What matters
    is that the metrics say what would actually happen rather than repeating the
    budget back.
    """
    scores = np.array([50.0] * 500 + list(np.linspace(60.0, 100.0, 500)))
    y = np.array([0] * 500 + [1] * 500, dtype=np.int_)

    metrics = evaluate(y, scores, budget=0.01)

    assert metrics.alert_rate == metrics.alert_count / scores.size


def test_metrics_at_a_perfect_ranking() -> None:
    y = np.array([0] * 90 + [1] * 10, dtype=np.int_)
    scores = np.array([1.0] * 90 + [99.0] * 10)

    metrics = evaluate(y, scores, threshold=50.0)

    assert metrics.pr_auc == pytest.approx(1.0)
    assert metrics.precision == pytest.approx(1.0)
    assert metrics.recall == pytest.approx(1.0)
    assert metrics.false_positive_rate == pytest.approx(0.0)
    assert metrics.confusion.true_positives == 10
    assert metrics.confusion.false_positives == 0


def test_recall_is_reported_per_planted_shape() -> None:
    y = np.array([1, 1, 1, 1, 0, 0], dtype=np.int_)
    scores = np.array([99.0, 99.0, 1.0, 1.0, 1.0, 1.0])
    labels = (
        "VELOCITY_BURST",
        "VELOCITY_BURST",
        "CARD_TESTING",
        "CARD_TESTING",
        "NORMAL",
        "NORMAL",
    )

    metrics = evaluate(y, scores, labels=labels, threshold=50.0)

    # An aggregate recall of 0.5 could be every shape found half the time, or one
    # shape always found and another never. Here it is the second.
    assert metrics.recall == pytest.approx(0.5)
    assert metrics.recall_by_label == {"CARD_TESTING": 0.0, "VELOCITY_BURST": 1.0}


def test_recall_by_label_is_empty_when_labels_are_not_supplied() -> None:
    y = np.array([0, 1], dtype=np.int_)
    assert evaluate(y, np.array([1.0, 99.0]), threshold=50.0).recall_by_label == {}


def test_an_empty_score_array_is_an_error_not_a_threshold() -> None:
    with pytest.raises(ValueError, match="no scores"):
        threshold_for_budget(np.array([], dtype=np.float64))


def test_the_default_budget_is_one_percent() -> None:
    assert ALERT_BUDGET == 0.01
