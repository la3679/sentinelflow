"""The split, which is where an evaluation quietly becomes meaningless.

Leakage does not fail a test suite or raise an exception. It makes every metric
better, and a model that looks good is not something anyone investigates. So the
absence of it is asserted directly rather than inferred from the code.
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
import pytest

from sentinelflow_scoring.training.dataset import load
from sentinelflow_scoring.training.splitting import (
    SplitError,
    accounts_overlap,
    cross_validation_folds,
    split,
)

SEED = 20260826


def test_no_account_appears_on_both_sides(export: Path) -> None:
    data = load(export)
    division = split(data, SEED)

    # The leak the group split exists to prevent: every planted shape is several
    # correlated rows on one account, so an account spanning both sides puts
    # near-duplicates of a test row into training.
    assert not accounts_overlap(data, division.train, division.test)


def test_training_never_sees_the_holdout_period(export: Path) -> None:
    data = load(export)
    division = split(data, SEED)

    assert data.times[division.train].max() < division.holdout_cutoff
    assert data.times[division.test].min() >= division.holdout_cutoff


def test_the_holdout_always_contains_positives(export: Path) -> None:
    data = load(export)
    division = split(data, SEED)

    # Stratification is what makes this hold. Without it, positives concentrated
    # in a minority of accounts can miss the holdout entirely and recall becomes
    # undefined - which is how this was found on real data.
    assert data.y[division.test].sum() > 0
    assert data.y[division.train].sum() > 0


def test_stratification_survives_a_change_of_seed(export: Path) -> None:
    """Not one lucky draw.

    A split that only works for the seed it was written against is not a split,
    it is a coincidence.
    """
    data = load(export)
    for seed in (1, 7, 42, 20260826, 99991):
        division = split(data, seed)
        assert data.y[division.test].sum() > 0
        assert not accounts_overlap(data, division.train, division.test)


def test_the_same_seed_gives_the_same_split(export: Path) -> None:
    data = load(export)
    first = split(data, SEED)
    second = split(data, SEED)

    assert np.array_equal(first.train, second.train)
    assert np.array_equal(first.test, second.test)
    assert first.holdout_cutoff == second.holdout_cutoff


def test_a_different_seed_gives_a_different_split(export: Path) -> None:
    data = load(export)
    assert not np.array_equal(split(data, 1).test, split(data, 2).test)


def test_refuses_a_dataset_with_too_few_accounts(export: Path) -> None:
    data = load(export)
    one_account = type(data)(
        x=data.x,
        y=data.y,
        groups=np.array(["ACC-000001"] * len(data), dtype=np.str_),
        times=data.times,
        labels=data.labels,
        rule_scores=data.rule_scores,
        feature_names=data.feature_names,
        manifest=data.manifest,
    )

    with pytest.raises(SplitError, match="group-disjoint split needs at least"):
        split(one_account, SEED)


def test_refuses_when_the_holdout_would_have_no_positives(export: Path) -> None:
    data = load(export)
    # Every positive moved to the earliest instant, so none can fall after the
    # cutoff however the accounts are drawn.
    times = data.times.copy()
    times[data.y == 1] = data.times.min()
    early_positives = type(data)(
        x=data.x,
        y=data.y,
        groups=data.groups,
        times=times,
        labels=data.labels,
        rule_scores=data.rule_scores,
        feature_names=data.feature_names,
        manifest=data.manifest,
    )

    with pytest.raises(SplitError, match="no planted shapes"):
        split(early_positives, SEED)


def test_folds_are_group_disjoint_too(export: Path) -> None:
    data = load(export)
    division = split(data, SEED)

    for fold_train, fold_test in cross_validation_folds(data, division.train, 5, SEED):
        # The same leak, one level down. Folds choose the model and the operating
        # point, so a leak here picks the wrong model rather than merely
        # flattering the chosen one.
        assert not accounts_overlap(data, fold_train, fold_test)
        assert set(fold_train.tolist()).isdisjoint(fold_test.tolist())


def test_folds_refuse_more_splits_than_accounts(export: Path) -> None:
    data = load(export)
    division = split(data, SEED)

    with pytest.raises(SplitError, match="group-disjoint"):
        cross_validation_folds(data, division.train, 10_000, SEED)
