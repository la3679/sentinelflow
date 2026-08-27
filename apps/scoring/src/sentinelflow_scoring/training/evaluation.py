"""Metrics, the operating point, and the rules baseline everything is measured against.

**PR-AUC is the headline and accuracy is not reported at all.** Under this class
imbalance a model that answers "not suspicious" to everything scores extremely
well on accuracy, and that is the model this project exists to argue against.
Leaving it out of the metrics object entirely is deliberate: a number that exists
gets quoted.

**The operating point is chosen against an alert-volume budget, not by maximising
F1.** An analyst team is a fixed-capacity queue. A threshold picked by maximising
F1 optimises an arithmetic property of the confusion matrix that nobody in
operations experiences, whereas "we can review one transaction in a hundred" is a
constraint someone actually has. F1 is reported beside the budgeted point rather
than used to choose it.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass, field
from typing import Any

import numpy as np
from numpy.typing import NDArray
from sklearn.metrics import (
    average_precision_score,
    brier_score_loss,
    confusion_matrix,
    fbeta_score,
    roc_auc_score,
)

#: Share of scored transactions an analyst team is assumed able to review. The
#: operating point is the threshold whose alert volume matches it. Invented for
#: this demo and stated as such — it is a capacity assumption, not a measurement,
#: and the model card says so rather than letting it read as one.
ALERT_BUDGET = 0.01

#: Recall matters more than precision when the cost of a missed case exceeds the
#: cost of a review, so F-beta is reported with beta > 1. Reported, never used to
#: select: see the module docstring.
FBETA = 2.0


@dataclass(frozen=True, slots=True)
class ConfusionCounts:
    """The confusion matrix at the selected threshold, named rather than a 2x2."""

    true_negatives: int
    false_positives: int
    false_negatives: int
    true_positives: int


@dataclass(frozen=True, slots=True)
class Metrics:
    """What a candidate scored. Deliberately without accuracy.

    :param pr_auc: average precision. The headline (ADR-0010 §5).
    :param roc_auc: secondary only. Under heavy imbalance it stays flatteringly
        high for a model with poor precision, because the negative class is so
        large that even many false positives barely move the false-positive rate.
    :param brier: calibration quality, lower is better. Present because ADR-0008
        §4's single threshold is only coherent if the score is calibrated, so a
        model may not be published without stating how well it is.
    :param threshold: the operating point, on the 0-to-100 contract scale.
    :param alert_rate: the share of transactions this threshold would alert on.
        The thing the budget constrains.
    :param recall_by_label: recall per planted shape. The number that says *which*
        patterns are found — an aggregate recall of 0.6 could be every shape found
        six times in ten, or four shapes always found and two never.
    """

    pr_auc: float
    roc_auc: float
    brier: float
    threshold: float
    precision: float
    recall: float
    f1: float
    fbeta: float
    false_positive_rate: float
    alert_rate: float
    alert_count: int
    positives: int
    negatives: int
    confusion: ConfusionCounts
    recall_by_label: dict[str, float] = field(default_factory=dict)

    def as_dict(self) -> dict[str, Any]:
        return asdict(self)


def evaluate(
    y_true: NDArray[np.int_],
    scores: NDArray[np.float64],
    *,
    labels: tuple[str, ...] = (),
    threshold: float | None = None,
    budget: float = ALERT_BUDGET,
) -> Metrics:
    """Scores a candidate on the 0-to-100 scale.

    :param scores: contract-scale scores, higher is riskier.
    :param threshold: the operating point. When ``None`` it is chosen from the
        alert budget over these same scores; the final report passes the
        threshold chosen on the training folds instead, so the holdout is not
        used to pick the point it then reports.
    """
    if threshold is None:
        threshold = threshold_for_budget(scores, budget)

    predicted = (scores >= threshold).astype(np.int_)
    matrix = confusion_matrix(y_true, predicted, labels=[0, 1])
    tn, fp, fn, tp = (int(value) for value in matrix.ravel())

    precision = tp / (tp + fp) if (tp + fp) else 0.0
    recall = tp / (tp + fn) if (tp + fn) else 0.0
    f1 = (2 * precision * recall / (precision + recall)) if (precision + recall) else 0.0

    return Metrics(
        # Average precision over the raw scores rather than the thresholded
        # predictions: PR-AUC is a property of the ranking, and computing it
        # after thresholding would throw away the ranking it measures.
        pr_auc=float(average_precision_score(y_true, scores)),
        roc_auc=float(roc_auc_score(y_true, scores)) if len(set(y_true.tolist())) > 1 else 0.0,
        brier=float(brier_score_loss(y_true, scores / 100.0)),
        threshold=float(threshold),
        precision=precision,
        recall=recall,
        f1=f1,
        fbeta=float(fbeta_score(y_true, predicted, beta=FBETA, zero_division=0.0)),
        false_positive_rate=fp / (fp + tn) if (fp + tn) else 0.0,
        alert_rate=float(predicted.mean()),
        alert_count=int(predicted.sum()),
        positives=int(y_true.sum()),
        negatives=int((y_true == 0).sum()),
        confusion=ConfusionCounts(tn, fp, fn, tp),
        recall_by_label=_recall_by_label(y_true, predicted, labels),
    )


def threshold_for_budget(scores: NDArray[np.float64], budget: float = ALERT_BUDGET) -> float:
    """The threshold whose alert volume matches the review budget.

    The quantile of the score distribution, so alerting on the top ``budget``
    share of transactions is what the threshold means by construction rather than
    by a search that might not converge.

    Ties are why this is not exact. Many transactions can share a score — a
    calibrated model over a small dataset produces plateaus — so the realised
    alert rate can exceed the budget. Reported as measured rather than forced:
    ``alert_rate`` in the metrics is what would actually happen, and a
    discrepancy is information about the score distribution rather than an error
    to hide.
    """
    if scores.size == 0:
        raise ValueError("Cannot choose a threshold from no scores")
    return float(np.quantile(scores, 1.0 - budget))


def _recall_by_label(
    y_true: NDArray[np.int_], predicted: NDArray[np.int_], labels: tuple[str, ...]
) -> dict[str, float]:
    """Recall per planted shape, over the positive rows only."""
    if not labels or len(labels) != y_true.size:
        return {}

    by_label: dict[str, list[int]] = {}
    for index, label in enumerate(labels):
        if y_true[index] == 1:
            by_label.setdefault(label, []).append(int(predicted[index]))

    return {label: sum(hits) / len(hits) for label, hits in sorted(by_label.items()) if hits}


def rules_baseline_scores(
    x: NDArray[np.float64], feature_names: tuple[str, ...]
) -> NDArray[np.float64]:
    """The transparent rules floor, on the contract's scale.

    **This is a stand-in for evaluation, not the shipped baseline.** ADR-0002 §3
    puts the production rules in ``apps/api``, because they must run in-process
    when the scoring service is unreachable. What is needed *here* is something to
    measure the models against, and it has to be the same shape of rule the API
    will run or the comparison is against a straw man.

    Deliberately crude and deliberately readable: a handful of additive signals a
    fraud analyst would recognise, each contributing a fixed weight. No fitting,
    no thresholds learned from the data — a "baseline" tuned on the training set
    is not a baseline, it is another model with fewer parameters.

    When ``apps/api``'s ruleset lands, this must be replaced by scoring the same
    ruleset rather than kept in parallel. Two rule implementations would drift,
    and the drift would show up as a model that beats a baseline nobody runs.
    """
    index = {name: position for position, name in enumerate(feature_names)}

    def column(name: str) -> NDArray[np.float64]:
        position = index.get(name)
        if position is None:
            return np.zeros(x.shape[0], dtype=np.float64)
        return x[:, position]

    score = np.zeros(x.shape[0], dtype=np.float64)
    score += 25.0 * (column("count_5m") >= 4)
    score += 20.0 * (column("amount_to_account_mean_ratio") >= 5.0)
    score += 15.0 * column("is_new_device")
    score += 15.0 * column("is_country_change")
    score += 10.0 * column("is_off_hours")
    score += 15.0 * (column("balance_drain_ratio") >= 0.5)
    score += 10.0 * (column("distinct_merchants_1h") >= 4)

    return np.clip(score, 0.0, 100.0)
