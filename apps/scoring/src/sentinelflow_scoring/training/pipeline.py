"""The training run: compare, select, fit, and write a registry entry.

The order matters and is ADR-0010 §5's, not a convenience:

1. Split group-disjoint by account **and** time-ordered.
2. Cross-validate every candidate on the training rows only, and take the
   operating point from those folds.
3. Fit each candidate on all training rows and score the holdout **once**, at the
   threshold chosen in step 2.
4. Select by the rule fixed before any of it was measured.

**The holdout never chooses anything.** Picking a threshold on the same rows the
metrics are reported over is how a report becomes an advertisement — the number
would be the best achievable on those rows rather than what the threshold would do
on rows nobody had seen.

**Selection can conclude that nothing ships.** If no eligible model beats the
rules baseline by the stated margin, the baseline is what runs and the result is
recorded as evaluated-and-not-promoted. Having built a model is not a reason to
serve one.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np
from numpy.typing import NDArray

from sentinelflow_scoring.features import FEATURE_VERSION
from sentinelflow_scoring.training import plots, registry
from sentinelflow_scoring.training.candidates import (
    CANDIDATES,
    Candidate,
    positive_probability,
    to_contract_score,
)
from sentinelflow_scoring.training.dataset import TrainingData
from sentinelflow_scoring.training.evaluation import (
    ALERT_BUDGET,
    Metrics,
    evaluate,
    rules_baseline_scores,
    threshold_for_budget,
)
from sentinelflow_scoring.training.splitting import Split, cross_validation_folds, split

#: How much a model must beat the rules baseline's PR-AUC by, in absolute terms,
#: before it may ship. Fixed here rather than argued about after the numbers are
#: in (ADR-0010 §5). Absolute rather than relative: a relative margin over a tiny
#: baseline is satisfied by noise.
MINIMUM_MARGIN_OVER_RULES = 0.05

#: Cross-validation folds for model selection.
FOLDS = 5

#: The fewest positives a holdout may contain and still support a published
#: figure.
#:
#: **Found by running it.** The DEMO profile produces a holdout with three
#: positives, and every metric over three positives is a coin toss wearing four
#: decimal places: the selected model's PR-AUC moved from 0.06 to 0.39 on the
#: difference between finding one of them and none. Nothing about those numbers is
#: fabricated, and reporting them would still be dishonest — they would be
#: presented as evidence while being incapable of supporting a conclusion.
#:
#: Twenty is not a statistical result; it is a floor below which the pipeline
#: refuses to publish rather than a claim that twenty is enough. The model card
#: states the count either way, so a reader can judge the interval themselves.
MINIMUM_HOLDOUT_POSITIVES = 20

#: The name the rules floor is reported under. Not a candidate — it cannot be
#: selected, because it is what the API runs anyway when scoring is unavailable.
RULES_BASELINE = "rules-baseline"


@dataclass(frozen=True, slots=True)
class CandidateResult:
    """One candidate's cross-validation spread and its holdout metrics."""

    name: str
    eligible: bool
    description: str
    fold_pr_auc: list[float]
    holdout: Metrics
    threshold: float

    @property
    def usable_folds(self) -> list[float]:
        """Folds that produced a defined PR-AUC.

        A fold with no positives has none, and :func:`_fold_pr_auc` reports that
        as ``nan`` rather than inventing a value. Excluding those here keeps a
        single degenerate fold from turning the mean and the spread into ``nan``
        and silently taking every candidate out of the comparison.
        """
        return [value for value in self.fold_pr_auc if not np.isnan(value)]

    @property
    def fold_mean(self) -> float:
        usable = self.usable_folds
        return float(np.mean(usable)) if usable else 0.0

    @property
    def fold_spread(self) -> float:
        """Max minus min across folds. ADR-0010 §5 defines a tie in these terms."""
        usable = self.usable_folds
        return float(max(usable) - min(usable)) if usable else 0.0


@dataclass(frozen=True, slots=True)
class Selection:
    """What the pre-registered rule concluded.

    :param model: the chosen candidate, or ``None`` when the rules ship alone.
    :param reason: written into the model card and the evaluation document, so
        the decision is legible without rerunning anything.
    """

    model: CandidateResult | None
    reason: str


@dataclass(frozen=True, slots=True)
class TrainingResult:
    split: Split
    threshold: float
    rules: Metrics
    candidates: list[CandidateResult]
    selection: Selection
    estimator: object | None

    def metrics_document(self) -> dict[str, Any]:
        return {
            "featureVersion": FEATURE_VERSION,
            "alertBudget": ALERT_BUDGET,
            "operatingPoint": self.threshold,
            "minimumMarginOverRules": MINIMUM_MARGIN_OVER_RULES,
            "holdoutCutoff": str(self.split.holdout_cutoff),
            "trainRows": int(self.split.train.size),
            "holdoutRows": int(self.split.test.size),
            "selected": self.selection.model.name if self.selection.model else None,
            "selectionReason": self.selection.reason,
            RULES_BASELINE: self.rules.as_dict(),
            "candidates": {
                result.name: {
                    "eligible": result.eligible,
                    "description": result.description,
                    "operatingPoint": result.threshold,
                    "foldPrAuc": result.fold_pr_auc,
                    "foldMean": result.fold_mean,
                    "foldSpread": result.fold_spread,
                    "holdout": result.holdout.as_dict(),
                }
                for result in self.candidates
            },
        }


def train(data: TrainingData, seed: int) -> TrainingResult:
    """Runs the comparison and applies the selection rule."""
    division = split(data, seed)
    train_x, train_y = data.x[division.train], data.y[division.train]
    test_x, test_y = data.x[division.test], data.y[division.test]
    test_labels = tuple(data.labels[index] for index in division.test.tolist())

    folds = cross_validation_folds(data, division.train, FOLDS, seed)

    rules = evaluate(
        test_y,
        rules_baseline_scores(test_x, data.feature_names),
        labels=test_labels,
        threshold=threshold_for_budget(
            rules_baseline_scores(data.x[division.train], data.feature_names), ALERT_BUDGET
        ),
    )

    results: list[CandidateResult] = []
    fitted: dict[str, object] = {}
    for candidate in CANDIDATES:
        fold_scores = [
            _fold_pr_auc(candidate, data, fold_train, fold_test, seed)
            for fold_train, fold_test in folds
        ]

        # Each candidate gets its own operating point, from its own out-of-fold
        # score distribution on the TRAINING rows. One shared threshold would be
        # meaningless: two models can rank identically and still place their
        # scores at completely different absolute values, so a threshold taken
        # from one and applied to another alerts on far more or far fewer than
        # the budget — and the alert-volume comparison, which is the whole point
        # of budgeting, would be comparing two different volumes.
        #
        # Out-of-fold rather than in-fold, because a model has seen its training
        # rows: a threshold chosen from optimistic scores alerts on far more than
        # the budget the moment it meets rows it has not.
        threshold = threshold_for_budget(
            _out_of_fold_scores(candidate, data, folds, seed), ALERT_BUDGET
        )

        estimator = candidate.build(seed)
        _fit(estimator, train_x, train_y)
        scores = to_contract_score(positive_probability(estimator, test_x))

        results.append(
            CandidateResult(
                name=candidate.name,
                eligible=candidate.eligible,
                description=candidate.description,
                fold_pr_auc=fold_scores,
                holdout=evaluate(test_y, scores, labels=test_labels, threshold=threshold),
                threshold=threshold,
            )
        )
        fitted[candidate.name] = estimator

    selection = select(results, rules)
    return TrainingResult(
        split=division,
        threshold=selection.model.threshold if selection.model else rules.threshold,
        rules=rules,
        candidates=results,
        selection=selection,
        estimator=fitted[selection.model.name] if selection.model else None,
    )


def select(results: list[CandidateResult], rules: Metrics) -> Selection:
    """The rule ADR-0010 §5 fixed before any of this was measured.

    Three clauses, applied in order:

    1. A model ships only if it beats the rules baseline's PR-AUC by at least
       :data:`MINIMUM_MARGIN_OVER_RULES`. If none does, the rules ship alone.
    2. Between qualifying models, the best holdout PR-AUC leads.
    3. **Unless the gap to the simpler model is inside the fold spread**, in which
       case it is not distinguishable from fold noise and the simpler model wins.
       "Simpler" is the declaration order in ``CANDIDATES``, which puts logistic
       regression first deliberately.

    Before any of that, the holdout has to be able to support a conclusion at all
    — see :data:`MINIMUM_HOLDOUT_POSITIVES`.
    """
    positives = rules.positives
    if positives < MINIMUM_HOLDOUT_POSITIVES:
        return Selection(
            model=None,
            reason=(
                f"The holdout contains {positives} positive example(s), below the floor of "
                f"{MINIMUM_HOLDOUT_POSITIVES} needed to support a published figure. Every metric "
                f"over so few positives moves by large amounts on a single example, so promoting "
                f"a model on them would be presenting noise as evidence. Nothing is promoted. "
                f"Generate a larger profile — SENTINELFLOW_SEED_PROFILE=LOCAL — re-export, and "
                f"run this again."
            ),
        )

    eligible = [result for result in results if result.eligible]
    qualifying = [
        result
        for result in eligible
        if result.holdout.pr_auc >= rules.pr_auc + MINIMUM_MARGIN_OVER_RULES
    ]

    if not qualifying:
        best_pr_auc = max((r.holdout.pr_auc for r in eligible), default=0.0)
        return Selection(
            model=None,
            reason=(
                f"No eligible model beat the rules baseline (PR-AUC {rules.pr_auc:.4f}) by the "
                f"required {MINIMUM_MARGIN_OVER_RULES:.2f}; the best managed {best_pr_auc:.4f}. "
                "The rules baseline ships alone, and the models are recorded as evaluated and "
                "not promoted. Having built a model is not a reason to serve one."
            ),
        )

    order = {candidate.name: position for position, candidate in enumerate(CANDIDATES)}
    qualifying.sort(key=lambda result: order[result.name])
    simplest = qualifying[0]
    best = max(qualifying, key=lambda result: result.holdout.pr_auc)

    if best.name == simplest.name:
        return Selection(
            model=simplest,
            reason=(
                f"{simplest.name} has the best holdout PR-AUC ({simplest.holdout.pr_auc:.4f}) "
                f"among qualifying models and is also the simplest."
            ),
        )

    gap = best.holdout.pr_auc - simplest.holdout.pr_auc
    spread = max(best.fold_spread, simplest.fold_spread)
    if gap <= spread:
        return Selection(
            model=simplest,
            reason=(
                f"{best.name} leads {simplest.name} by {gap:.4f} PR-AUC, which is inside the "
                f"cross-validation fold spread ({spread:.4f}) and therefore not distinguishable "
                f"from fold noise. ADR-0010 §5 gives a tie to the simpler model, for its "
                f"inference cost and its per-feature explainability."
            ),
        )

    return Selection(
        model=best,
        reason=(
            f"{best.name} beats {simplest.name} by {gap:.4f} PR-AUC, which exceeds the fold "
            f"spread ({spread:.4f}), so the difference is not fold noise."
        ),
    )


def write_entry(
    result: TrainingResult,
    data: TrainingData,
    root: Path,
    model_version: str,
    seed: int,
    lock_sha256: str,
    model_card: str,
) -> Path | None:
    """Writes the registry entry and its plots. ``None`` when no model ships."""
    if result.selection.model is None or result.estimator is None:
        return None

    chosen = result.selection.model
    directory = registry.entry_directory(root, chosen.name, model_version)
    directory.mkdir(parents=True, exist_ok=True)

    plot_directory = directory / "plots"
    plot_directory.mkdir(exist_ok=True)
    test_y = data.y[result.split.test]
    curves = {
        RULES_BASELINE: (
            test_y,
            rules_baseline_scores(data.x[result.split.test], data.feature_names),
        )
    }
    for candidate in CANDIDATES:
        estimator = candidate.build(seed)
        _fit(estimator, data.x[result.split.train], data.y[result.split.train])
        curves[candidate.name] = (
            test_y,
            to_contract_score(positive_probability(estimator, data.x[result.split.test])),
        )

    plots.precision_recall(plot_directory, curves)
    plots.reliability(plot_directory, test_y, curves[chosen.name][1], chosen.name)

    registry.write(
        directory=directory,
        estimator=result.estimator,
        manifest_without_checksum={
            "model_name": chosen.name,
            "model_version": model_version,
            "algorithm": chosen.description,
            "feature_version": FEATURE_VERSION,
            "context_version": int(data.manifest["contextVersion"]),
            "trained_at": registry.now_iso(),
            "seed": seed,
            "hyperparameters": _hyperparameters(result.estimator),
            "feature_names": list(data.feature_names),
            "dataset_sha256": str(data.manifest["datasetSha256"]),
            "dataset_generator_version": str(data.manifest["generatorVersion"]),
            "dataset_seed": int(data.manifest["seed"]),
            "dataset_profile": str(data.manifest["profile"]),
            "dataset_examples": len(data),
            "split_strategy": (
                "group-disjoint by accountReference, holdout restricted to transactions at or "
                "after the time cutoff"
            ),
            "holdout_cutoff": str(result.split.holdout_cutoff),
            "operating_point": result.threshold,
            "alert_budget": ALERT_BUDGET,
            "environment_lock_sha256": lock_sha256,
        },
        metrics=result.metrics_document(),
        model_card=model_card,
    )
    return directory


def _fit(estimator: object, x: NDArray[np.float64], y: NDArray[np.int_]) -> None:
    """Fits supervised and unsupervised candidates through one call site.

    ``IsolationForest`` takes no labels. Passing them anyway is accepted and
    ignored by scikit-learn, which is exactly the kind of silently-tolerated call
    that makes a reader think the model is supervised.
    """
    from sklearn.ensemble import IsolationForest

    final = estimator.steps[-1][1] if hasattr(estimator, "steps") else estimator
    if isinstance(final, IsolationForest):
        estimator.fit(x)  # type: ignore[attr-defined]
    else:
        estimator.fit(x, y)  # type: ignore[attr-defined]


def _out_of_fold_scores(
    candidate: Candidate,
    data: TrainingData,
    folds: list[tuple[NDArray[np.int_], NDArray[np.int_]]],
    seed: int,
) -> NDArray[np.float64]:
    """Scores for training rows, each produced by a model that did not see it.

    In-fold scores would be optimistic — the model has seen those rows — and a
    threshold chosen from an optimistic distribution alerts on far more than the
    budget once it meets rows it has not.
    """
    collected: list[NDArray[np.float64]] = []
    for fold_train, fold_test in folds:
        estimator = candidate.build(seed)
        _fit(estimator, data.x[fold_train], data.y[fold_train])
        collected.append(to_contract_score(positive_probability(estimator, data.x[fold_test])))
    return np.concatenate(collected)


def _fold_pr_auc(
    candidate: Candidate,
    data: TrainingData,
    fold_train: NDArray[np.int_],
    fold_test: NDArray[np.int_],
    seed: int,
) -> float:
    from sklearn.metrics import average_precision_score

    estimator = candidate.build(seed)
    _fit(estimator, data.x[fold_train], data.y[fold_train])
    scores = to_contract_score(positive_probability(estimator, data.x[fold_test]))

    y_true = data.y[fold_test]
    if not y_true.any():
        # A fold with no positives has no PR-AUC. Returning 0.0 would drag the
        # mean down as though the model had failed; excluding it is honest, and
        # StratifiedGroupKFold makes it rare rather than impossible.
        return float("nan")
    return float(average_precision_score(y_true, scores))


def _hyperparameters(estimator: object) -> dict[str, Any]:
    """The estimator's own parameters, stringified so the manifest stays JSON.

    Recorded because ADR-0010 §6 requires a training run to be reconstructible,
    and "which model" is not enough — the same algorithm at a different learning
    rate is a different model producing different scores.
    """
    params = estimator.get_params(deep=False)  # type: ignore[attr-defined]
    return {key: str(value) for key, value in sorted(params.items())}
