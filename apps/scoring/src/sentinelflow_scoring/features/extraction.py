"""Turning a request into a feature vector.

Versioned, deterministic, and free of leakage. Those three words carry the whole
design:

**Versioned.** :data:`FEATURE_VERSION` is returned on every score and persisted
on every assessment. A feature definition can change under a model that did not,
and a score computed from different features is a different score even when the
model is byte-identical — so "which model" is not enough to reconstruct a
decision months later, and "which features" has to be recorded beside it.

**Deterministic.** Pure functions over the request. No clock, no randomness, no
state between calls. ``datetime.now()`` anywhere in here would make a feature
depend on when it was computed rather than on what happened, and the same
transaction would score differently on a retry.

**Free of leakage, structurally rather than by intention.** Every window is
measured backwards from ``transaction.occurred_at``, and anything in the context
at or after that instant is discarded before a feature sees it. That is not
defensive coding against a hypothetical: the API sends the account's recent
history, and "recent" is relative to when the API asked, not to when the
transaction happened. A replayed or late-arriving transaction therefore
legitimately carries history from after it — and a feature computed over that is
a model being shown the future.
"""

from __future__ import annotations

import math
from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from datetime import datetime, timedelta
from decimal import Decimal

from sentinelflow_scoring.features.schema import RecentTransaction, ScoreRequest

#: Bump when a change here would produce a different vector from the same
#: request. Semantic: a new feature is a minor bump, a redefinition is a major
#: one, because a redefinition silently changes what every stored score meant.
FEATURE_VERSION = "1.0.0"

#: The canonical column order, and the reason it is declared rather than derived.
#:
#: A model is fitted on a matrix whose columns are in *some* order, and serving
#: rebuilds that matrix from a dict. If the two orders disagree the estimator
#: still returns a number, the number is still between 0 and 100, and it is an
#: answer about different quantities — the one failure mode in this service that
#: produces no error at all. Declaring the order here gives the loader something
#: to assert a manifest's ``feature_names`` against before a model is used.
#:
#: Sorted, matching :func:`sentinelflow_scoring.training.dataset.load`, which
#: takes ``tuple(sorted(...))`` over the exported rows. The two are the same list
#: by construction and ``test_extraction.py`` asserts it rather than trusting it.
FEATURE_NAMES: tuple[str, ...] = (
    "account_age_days",
    "amount",
    "amount_to_account_mean_ratio",
    "balance_drain_ratio",
    "count_1m",
    "count_5m",
    "count_60m",
    "distinct_merchants_1h",
    "history_size",
    "is_channel_change",
    "is_country_change",
    "is_new_device",
    "is_new_merchant",
    "is_off_hours",
    "is_rounded_amount",
    "log_amount",
    "seconds_since_previous",
    "sum_1h",
    "sum_24h",
)

#: Windows the velocity features are counted over, shortest first.
_COUNT_WINDOWS: tuple[tuple[str, int], ...] = (
    ("count_1m", 60),
    ("count_5m", 300),
    ("count_60m", 3_600),
)

#: Windows the amount features are summed over.
_SUM_WINDOWS: tuple[tuple[str, int], ...] = (
    ("sum_1h", 3_600),
    ("sum_24h", 86_400),
)

#: Local hours treated as "off". Deliberately narrow: 02:00 to 04:59 is unusual
#: for retail activity in a way 23:00 is not, and a wide window would make the
#: feature fire on ordinary evening spending.
_OFF_HOURS = frozenset({2, 3, 4})

#: A "rounded" amount is a whole multiple of this. Repeated round transfers are
#: a documented pattern; 100.00 and 500.00 are round in a way 137.42 is not.
_ROUNDING_UNIT = Decimal(100)

#: Channels that have no device by nature. A null device on one of these is a
#: real answer, not a missing one, and must not read as "new device".
_DEVICELESS_CHANNELS = frozenset({"ATM", "DIRECT_DEBIT"})


@dataclass(frozen=True, slots=True)
class FeatureExtraction:
    """A feature vector, and what the extractor had to work around.

    ``warnings`` is present and possibly empty rather than optional, so a caller
    never has to distinguish "nothing to report" from "the field was omitted".
    """

    features: dict[str, float]
    warnings: tuple[str, ...]


def extract(request: ScoreRequest) -> FeatureExtraction:
    """Compute the feature vector for one transaction.

    :raises ValueError: never. A context too short or too truncated to support a
        feature produces a warning and a defensible value, not an exception — a
        scoring service that refused to answer whenever history was thin would be
        unavailable exactly when an account is new, which is when a score matters
        most.
    """
    transaction = request.transaction
    context = request.account_context
    at = transaction.occurred_at

    history = _history_before(context.recent_transactions, at)
    warnings = list(_warnings_for(request, history))

    amount = transaction.amount.decimal
    features: dict[str, float] = {
        "amount": float(amount),
        # log1p rather than log: an amount can legitimately be very small, and
        # log(0.01) is a large negative number that dominates a linear model for
        # a transaction nobody would look at twice.
        "log_amount": math.log1p(float(abs(amount))),
        "is_rounded_amount": _boolean(amount % _ROUNDING_UNIT == 0),
        "is_off_hours": _boolean(at.hour in _OFF_HOURS),
        "account_age_days": _account_age_days(context.account_opened_at, at),
        "history_size": float(len(history)),
    }

    for name, seconds in _COUNT_WINDOWS:
        features[name] = float(len(_within(history, at, seconds)))

    for name, seconds in _SUM_WINDOWS:
        features[name] = float(sum(item.amount.decimal for item in _within(history, at, seconds)))

    features["amount_to_account_mean_ratio"] = _amount_ratio(amount, history)
    features["seconds_since_previous"] = _seconds_since_previous(history, at)
    features["is_new_merchant"] = _boolean(
        transaction.merchant_reference not in {item.merchant_reference for item in history}
    )
    features["is_new_device"] = _new_device(
        transaction.device_reference, transaction.channel, history
    )
    features["is_country_change"] = _country_change(transaction.origin_country, history)
    features["is_channel_change"] = _channel_change(transaction.channel, history)
    features["distinct_merchants_1h"] = float(
        len({item.merchant_reference for item in _within(history, at, 3_600)})
    )
    features["balance_drain_ratio"] = _balance_drain_ratio(amount, context.current_balance.decimal)

    return FeatureExtraction(features=features, warnings=tuple(warnings))


# --------------------------------------------------------------------------- #
# Leakage prevention
# --------------------------------------------------------------------------- #


def _history_before(
    recent: Sequence[RecentTransaction], at: datetime
) -> tuple[RecentTransaction, ...]:
    """Everything that happened strictly before the transaction being scored.

    **This is the leakage guard, and it is not theoretical.** The API sends an
    account's recent history as of when it asked. For a transaction ingested
    immediately that is the same thing as "before it"; for a replayed scenario or
    a late arrival it is not, and the context legitimately contains transactions
    from after the one being scored. A feature computed over those is a model
    being shown the future, which produces an evaluation that looks excellent and
    means nothing.

    Strictly before, not at-or-before: a transaction at the same instant cannot be
    known to have preceded this one, and including it would make the feature
    depend on tie-breaking.
    """
    return tuple(item for item in recent if item.occurred_at < at)


def _within(
    history: Sequence[RecentTransaction], at: datetime, seconds: int
) -> tuple[RecentTransaction, ...]:
    """History inside a window ending at ``at``. Half-open: ``(at - w, at)``."""
    floor = at - timedelta(seconds=seconds)
    return tuple(item for item in history if item.occurred_at > floor)


# --------------------------------------------------------------------------- #
# Features that need more than a filter
# --------------------------------------------------------------------------- #


def _amount_ratio(amount: Decimal, history: Sequence[RecentTransaction]) -> float:
    """This amount against what this account usually spends.

    Returns 1.0 — "entirely ordinary" — when there is no history to compare
    against. A new account is not a suspicious account, and a default that read
    as an extreme ratio would make every account's first transaction its most
    alarming.
    """
    if not history:
        return 1.0
    # An explicit Decimal accumulator, because sum() starts at int 0 and the
    # result is then Decimal | int - which divides into something mypy cannot
    # narrow, and which would silently admit a float into a money calculation.
    total = _total_of(item.amount.decimal for item in history)
    mean = total / Decimal(len(history))
    if mean == 0:
        return 1.0
    return float(abs(amount) / mean)


def _seconds_since_previous(history: Sequence[RecentTransaction], at: datetime) -> float:
    """Time since this account's previous transaction.

    Returns the lookback ceiling rather than 0 when there is no previous one.
    Zero would mean "immediately after the last one", which is the strongest
    velocity signal there is — precisely backwards for an account that has never
    transacted.
    """
    if not history:
        return float(86_400)
    # History is newest first and already filtered to before `at`, so the first
    # element is the previous transaction.
    return max(0.0, (at - history[0].occurred_at).total_seconds())


def _new_device(device: str | None, channel: str, history: Sequence[RecentTransaction]) -> float:
    """Whether this device is one the account has not used.

    An ATM or direct debit has no device by nature, so a null there is a real
    answer and not a new one. Treating it as "new device" would fire the feature
    on every cash withdrawal an account ever made.
    """
    if channel in _DEVICELESS_CHANNELS or device is None:
        return 0.0
    return _boolean(device not in {item.device_reference for item in history})


def _country_change(country: str, history: Sequence[RecentTransaction]) -> float:
    """Whether this country differs from the account's most recent one."""
    if not history:
        return 0.0
    return _boolean(country != history[0].origin_country)


def _channel_change(channel: str, history: Sequence[RecentTransaction]) -> float:
    """Whether this channel differs from the account's most recent one."""
    if not history:
        return 0.0
    return _boolean(channel != history[0].channel)


def _balance_drain_ratio(amount: Decimal, balance: Decimal) -> float:
    """How much of the balance this transaction moves.

    Zero when the balance is zero or negative: a ratio against a non-positive
    denominator is not a large number, it is an undefined one, and returning
    something enormous would make an overdrawn account permanently alarming.
    """
    if balance <= 0:
        return 0.0
    return float(abs(amount) / balance)


def _account_age_days(opened_at: datetime, at: datetime) -> float:
    """Days between the account opening and this transaction. Never negative."""
    return max(0.0, (at - opened_at).total_seconds() / 86_400)


def _boolean(value: bool) -> float:
    """Booleans enter the vector as 0.0 or 1.0, so every feature is one type."""
    return 1.0 if value else 0.0


def _total_of(amounts: Iterable[Decimal]) -> Decimal:
    """Sum money as money.

    ``sum()`` starts at the integer ``0``, so its result is ``Decimal | int`` and
    the next operation on it is whatever Python decides. Money never becomes a
    float here by accident (ADR-0007), and an explicit accumulator is how that is
    a property of the code rather than of the inputs.
    """
    total = Decimal(0)
    for amount in amounts:
        total += abs(amount)
    return total


# --------------------------------------------------------------------------- #
# Warnings
# --------------------------------------------------------------------------- #


def _warnings_for(request: ScoreRequest, history: Sequence[RecentTransaction]) -> Iterable[str]:
    """What the extractor had to work around.

    Each of these is a case where a number is still returned and means something
    narrower than its name. Saying so is the difference between a caller knowing
    the score is thin and a caller believing it is not.
    """
    context = request.account_context
    widest = max(seconds for _, seconds in (*_COUNT_WINDOWS, *_SUM_WINDOWS))

    if context.lookback_window_seconds < widest:
        yield (
            f"lookback window is {context.lookback_window_seconds}s; features defined over "
            f"{widest}s are computed from less history than they name"
        )

    if context.truncated:
        yield ("account context was truncated; counts and sums are floors rather than exact values")

    if not history:
        yield "no prior history before this transaction; ratio and recency features use defaults"

    dropped = len(context.recent_transactions) - len(history)
    if dropped > 0:
        # Not an error. It is the normal case for a replayed scenario, and the
        # count is worth reporting so a thin score is attributable to it.
        yield (
            f"{dropped} context transaction(s) at or after the scored transaction were excluded "
            "to prevent leakage"
        )
