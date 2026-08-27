"""Splitting the dataset so the evaluation means something.

ADR-0010 §3 fixes two properties, and both are needed because neither subsumes
the other.

**Group-disjoint on the account.** Every planted shape is several correlated
transactions on one account — a velocity burst, a card-testing run, a drain — and
each becomes a labelled row whose features are computed from the others.
Splitting an account puts near-duplicates of a test row into training, and the
model is then rewarded for recognising one specific burst rather than the shape of
bursts. This does not merely permit inflated metrics, it produces them.

**Time-ordered.** The held-out set is drawn from the later part of the generated
window. Group-disjointness alone would happily train on traffic from after the
test period, which production can never do.

**They are combined rather than chosen between**, and the combination is the
awkward part: an account's transactions span the whole window, so "later
transactions" and "held-out accounts" cut across each other. The resolution is to
hold out *accounts*, and then to hold out only the *later* transactions of those
accounts — training keeps every transaction of a training account, plus nothing
at all from a held-out one. That is stricter than either property alone.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np
from numpy.typing import NDArray
from sklearn.model_selection import StratifiedGroupKFold

from sentinelflow_scoring.training.dataset import TrainingData


class SplitError(RuntimeError):
    """The dataset cannot be split in a way that would mean anything."""


@dataclass(frozen=True, slots=True)
class Split:
    """Indices into a :class:`TrainingData`.

    :param train: rows to fit on.
    :param test: rows to report headline metrics on. Group-disjoint from
        ``train`` **and** later in time.
    :param holdout_cutoff: the instant the holdout begins. Recorded in the
        manifest, because "the later part of the window" is not reproducible
        unless the boundary is written down.
    """

    train: NDArray[np.int_]
    test: NDArray[np.int_]
    holdout_cutoff: np.datetime64

    def __post_init__(self) -> None:
        if self.train.size == 0 or self.test.size == 0:
            raise SplitError(
                "One side of the split is empty. A metric computed over no rows is not a "
                "small number, it is undefined, and reporting it would be inventing one."
            )


#: Share of accounts held out for the final evaluation. A quarter rather than a
#: fifth because the split spends *accounts* rather than rows, and the positive
#: class is a small minority: too small a holdout leaves too few planted shapes
#: to say anything about recall.
HOLDOUT_ACCOUNT_SHARE = 0.25

#: Where the time boundary falls within the generated window. The median rather
#: than a later quantile, and that choice is forced by the data rather than
#: preferred: planted shapes are sparse and each occupies a narrow window, so
#: pushing the boundary later leaves a holdout with too few positives — sometimes
#: none — and a recall figure over no positives is undefined.
HOLDOUT_TIME_QUANTILE = 0.5


def split(data: TrainingData, seed: int) -> Split:
    """Group-disjoint by account and time-ordered.

    **The account sample is stratified on whether an account carries a planted
    shape after the cutoff**, and that is not a refinement — it is what makes the
    split work at all. Each planted shape sits on exactly one account and occupies
    a narrow window, so positives are concentrated in a small minority of accounts
    *and* in a small part of each account's timeline. An unstratified quarter of
    accounts intersected with a time boundary therefore produces an empty holdout
    routinely rather than unluckily.

    Both weaker versions were tried against the DEMO profile and both failed on
    real data: an unstratified draw, and one stratified merely on "carries a shape
    at all". In the second, all seven held-out positive-carrying accounts had
    their shapes before the cutoff, leaving 40 late positives in the dataset and
    none of them in the holdout.

    **Stratifying on the class is not leakage.** It decides which rows are
    evaluated on, never what the model is shown: no estimator sees a stratum, and
    the features and labels of every row are unchanged by which side it landed on.
    What it buys is a holdout that can be reported honestly instead of one whose
    recall is undefined.

    **Training is also restricted to before the cutoff.** Holding out later
    transactions is only meaningful if the model has not been fitted on traffic
    from that period — otherwise the holdout is "later" in name while the model
    has already seen what happened then.

    :param seed: which accounts are held out is a deterministic function of this,
        so a rerun evaluates on the same accounts and the metrics are comparable.
    :raises SplitError: if either side would be empty, or if the holdout contains
        no positive examples — a recall figure over zero positives is undefined,
        and reporting it as 0.0 or 1.0 would both be inventions.
    """
    accounts = np.unique(data.groups)
    if accounts.size < 2:
        raise SplitError(
            f"{accounts.size} account(s) in the dataset. A group-disjoint split needs at least "
            "two, and a meaningful one needs many more — generate a larger profile."
        )

    # The cutoff first: which accounts are worth holding out depends on it.
    cutoff = np.quantile(data.times.astype("datetime64[s]").astype(np.int64), HOLDOUT_TIME_QUANTILE)
    holdout_cutoff = np.datetime64(int(cutoff), "s")
    is_later = data.times >= holdout_cutoff

    late_positive = set(data.groups[(data.y == 1) & is_later].tolist())
    with_late_shapes = np.array([a for a in accounts.tolist() if a in late_positive])
    others = np.array([a for a in accounts.tolist() if a not in late_positive])

    rng = np.random.default_rng(seed)
    held_out = _sample(rng, with_late_shapes, HOLDOUT_ACCOUNT_SHARE) | _sample(
        rng, others, HOLDOUT_ACCOUNT_SHARE
    )

    is_held_out_account = np.array([group in held_out for group in data.groups.tolist()])

    # Neither side sees the other's accounts, and neither side sees the other's
    # time period. Transactions that fall in neither — a training account's late
    # traffic, a held-out account's early traffic — are discarded rather than
    # reused, which is what makes both properties hold at once.
    train = np.flatnonzero(~is_held_out_account & ~is_later)
    test = np.flatnonzero(is_held_out_account & is_later)

    if test.size == 0:
        raise SplitError(
            "No held-out account has a transaction after the time cutoff, so the holdout is "
            "empty. The generated window is too short, or too few accounts carry late traffic."
        )
    if not data.y[test].any():
        raise SplitError(
            "The holdout contains no planted shapes even after stratifying the account sample "
            "on them. Recall over zero positives is undefined, and reporting it as either 0.0 "
            "or 1.0 would be an invented number. Generate a larger profile: "
            f"{len(late_positive)} of {accounts.size} accounts carry a shape after the cutoff."
        )
    if not data.y[train].any():
        raise SplitError(
            "The training set contains no planted shapes, so there is nothing for a supervised "
            "model to learn. Generate a larger profile."
        )

    return Split(train=train, test=test, holdout_cutoff=holdout_cutoff)


def _sample(rng: np.random.Generator, accounts: NDArray[np.str_], share: float) -> set[str]:
    """A deterministic share of one stratum.

    At least one account whenever the stratum is non-empty: rounding a small
    positive-carrying stratum down to zero is exactly the case this stratification
    exists to prevent.
    """
    if accounts.size == 0:
        return set()
    shuffled = accounts.copy()
    rng.shuffle(shuffled)
    count = max(1, round(accounts.size * share))
    return set(shuffled[:count].tolist())


def cross_validation_folds(
    data: TrainingData, train: NDArray[np.int_], splits: int, seed: int
) -> list[tuple[NDArray[np.int_], NDArray[np.int_]]]:
    """Grouped, stratified folds over the training rows, for model selection.

    ``StratifiedGroupKFold`` rather than ``GroupKFold``: the positive class is a
    small minority, and folds that ignore it can leave one with almost no
    positives, whose PR-AUC then swings wildly and drowns the comparison between
    candidates in noise. The fold *spread* is what ADR-0010 §5 defines "a tie" in
    terms of, so a spread inflated by an unstratified split would change which
    model ships.

    :raises SplitError: if there are fewer groups than requested folds.
    """
    groups = data.groups[train]
    distinct = np.unique(groups).size
    if distinct < splits:
        raise SplitError(
            f"{distinct} training account(s) cannot be divided into {splits} group-disjoint "
            f"folds. Generate a larger profile, or the folds will not be disjoint."
        )

    splitter = StratifiedGroupKFold(n_splits=splits, shuffle=True, random_state=seed)
    return [
        (train[fold_train], train[fold_test])
        for fold_train, fold_test in splitter.split(data.x[train], data.y[train], groups)
    ]


def accounts_overlap(data: TrainingData, left: NDArray[np.int_], right: NDArray[np.int_]) -> bool:
    """Whether two index sets share an account.

    Exists so leakage is asserted rather than assumed. Every split this module
    produces is checked with it in the tests, because the failure it guards
    against makes every metric look better and nothing look wrong.
    """
    return bool(set(data.groups[left].tolist()) & set(data.groups[right].tolist()))
