"""The selection rule, which ADR-0010 section 5 fixed before anything was measured.

The rule's whole purpose is that it cannot be rationalised after the numbers are
in, so these tests drive it with constructed numbers rather than with whatever a
training run happens to produce. Three outcomes matter and all three are asserted:
a model ships, the simpler model wins a tie, and **nothing ships**.
"""

from __future__ import annotations

import numpy as np
import pytest

from sentinelflow_scoring.training.evaluation import Metrics, evaluate
from sentinelflow_scoring.training.pipeline import (
    MINIMUM_HOLDOUT_POSITIVES,
    MINIMUM_MARGIN_OVER_RULES,
    CandidateResult,
    select,
)


def _metrics(pr_auc: float, positives: int = 100) -> Metrics:
    y = np.array([0] * 900 + [1] * positives, dtype=np.int_)
    metrics = evaluate(y, np.linspace(0.0, 100.0, y.size), threshold=99.0)
    return Metrics(**{**metrics.as_dict(), "pr_auc": pr_auc, "confusion": metrics.confusion})


def _candidate(
    name: str, pr_auc: float, folds: list[float], eligible: bool = True
) -> CandidateResult:
    return CandidateResult(
        name=name,
        eligible=eligible,
        description=name,
        fold_pr_auc=folds,
        holdout=_metrics(pr_auc),
        threshold=99.0,
    )


def test_nothing_ships_when_the_holdout_is_too_small() -> None:
    """The floor is checked before the margin, and it is checked at all.

    Found by running the real pipeline: the DEMO profile produces a holdout with
    three positives, and the selected model's PR-AUC moved from 0.06 to 0.39 on
    the difference between finding one of them and none.
    """
    rules = _metrics(0.1, positives=MINIMUM_HOLDOUT_POSITIVES - 1)
    results = [_candidate("logistic-regression", 0.99, [0.99] * 5)]

    selection = select(results, rules)

    assert selection.model is None
    assert "below the floor" in selection.reason
    assert str(MINIMUM_HOLDOUT_POSITIVES) in selection.reason


def test_nothing_ships_when_no_model_beats_the_rules_by_the_margin() -> None:
    rules = _metrics(0.80)
    results = [
        _candidate("logistic-regression", 0.80 + MINIMUM_MARGIN_OVER_RULES - 0.001, [0.8] * 5),
        _candidate("hist-gradient-boosting", 0.81, [0.8] * 5),
    ]

    selection = select(results, rules)

    # Having built a model is not a reason to serve one.
    assert selection.model is None
    assert "rules baseline ships alone" in selection.reason


def test_the_simpler_model_wins_a_gap_inside_the_fold_spread() -> None:
    rules = _metrics(0.10)
    results = [
        _candidate("logistic-regression", 0.80, [0.75, 0.85, 0.80, 0.82, 0.78]),
        # Ahead by 0.02, but the folds swing by 0.10 - so the lead is not
        # distinguishable from fold noise.
        _candidate("hist-gradient-boosting", 0.82, [0.75, 0.85, 0.80, 0.82, 0.78]),
    ]

    selection = select(results, rules)

    assert selection.model is not None
    assert selection.model.name == "logistic-regression"
    assert "fold noise" in selection.reason


def test_a_clear_lead_outside_the_fold_spread_wins() -> None:
    rules = _metrics(0.10)
    results = [
        _candidate("logistic-regression", 0.60, [0.60, 0.61, 0.60, 0.61, 0.60]),
        _candidate("hist-gradient-boosting", 0.90, [0.89, 0.90, 0.90, 0.89, 0.90]),
    ]

    selection = select(results, rules)

    assert selection.model is not None
    assert selection.model.name == "hist-gradient-boosting"
    assert "exceeds the fold spread" in selection.reason


def test_an_ineligible_model_never_wins_however_good_it_is() -> None:
    rules = _metrics(0.10)
    results = [
        _candidate("logistic-regression", 0.70, [0.70] * 5),
        _candidate("isolation-forest", 1.00, [1.00] * 5, eligible=False),
    ]

    selection = select(results, rules)

    # ADR-0010 section 4: an unbounded, dataset-relative anomaly score has no
    # stable meaning on the contract's scale, whatever it scores.
    assert selection.model is not None
    assert selection.model.name == "logistic-regression"


def test_a_fold_with_no_positives_does_not_poison_the_spread() -> None:
    result = _candidate("logistic-regression", 0.8, [0.9, float("nan"), 0.85, 0.88, 0.87])

    # A fold with no positives has no PR-AUC. Averaging nan in would take every
    # candidate out of the comparison at once, silently.
    assert not np.isnan(result.fold_mean)
    assert result.fold_spread == pytest.approx(0.05)
    assert len(result.usable_folds) == 4


def test_a_candidate_with_no_usable_folds_reports_zero_rather_than_nan() -> None:
    result = _candidate("logistic-regression", 0.8, [float("nan")] * 5)
    assert result.fold_mean == 0.0
    assert result.fold_spread == 0.0
