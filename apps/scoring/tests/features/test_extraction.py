"""What the feature pipeline promises.

Three claims are worth testing and none is visible by reading the code:
determinism, absence of leakage, and that each feature means what its name says
when the history is thin rather than plentiful.

The leakage tests are the ones that matter most. A leak produces an evaluation
that looks excellent, so it is invisible in every metric that would normally
catch a defect — the only thing that catches it is an assertion that the future
was excluded.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

import pytest

from sentinelflow_scoring.features import FEATURE_NAMES, FEATURE_VERSION, extract
from sentinelflow_scoring.features.schema import (
    AccountContext,
    Amount,
    RecentTransaction,
    ScoreRequest,
    TransactionToScore,
)

AT = datetime(2026, 8, 26, 14, 30, tzinfo=UTC)
OPENED = datetime(2024, 1, 1, tzinfo=UTC)


def money(value: str) -> Amount:
    return Amount(value=value, currency="GBP")


def scored(
    *,
    amount: str = "50.00",
    occurred_at: datetime = AT,
    merchant: str = "MER-0001",
    device: str | None = "DEV-0123456789ab",
    country: str = "GB",
    channel: str = "CARD_NOT_PRESENT",
) -> TransactionToScore:
    return TransactionToScore(
        transaction_id="01936b2a-7c4f-7000-8000-2f9c1d4e5a6b",
        account_reference="ACC-000001",
        merchant_reference=merchant,
        merchant_category_code="5411",
        type="PURCHASE",
        channel=channel,  # type: ignore[arg-type]
        amount=money(amount),
        origin_country=country,
        device_reference=device,
        occurred_at=occurred_at,
    )


def earlier(
    seconds_before: int,
    *,
    amount: str = "50.00",
    merchant: str = "MER-0001",
    device: str | None = "DEV-0123456789ab",
    country: str = "GB",
    channel: str = "CARD_NOT_PRESENT",
    base: datetime = AT,
) -> RecentTransaction:
    return RecentTransaction(
        occurred_at=base - timedelta(seconds=seconds_before),
        amount=money(amount),
        merchant_reference=merchant,
        device_reference=device,
        origin_country=country,
        channel=channel,  # type: ignore[arg-type]
        type="PURCHASE",
    )


def request(
    transaction: TransactionToScore | None = None,
    history: list[RecentTransaction] | None = None,
    *,
    lookback: int = 86_400,
    balance: str = "1000.00",
    truncated: bool = False,
    opened_at: datetime = OPENED,
) -> ScoreRequest:
    return ScoreRequest(
        transaction=transaction or scored(),
        account_context=AccountContext(
            context_version=1,
            lookback_window_seconds=lookback,
            account_opened_at=opened_at,
            current_balance=money(balance),
            recent_transactions=history or [],
            truncated=truncated,
        ),
    )


# --------------------------------------------------------------------------- #
# Determinism
# --------------------------------------------------------------------------- #


def test_the_same_request_produces_the_same_vector() -> None:
    payload = request(history=[earlier(30), earlier(600), earlier(7_200)])

    # Twice, not once. A clock read or a set iteration order anywhere in the
    # pipeline would show up here and nowhere else.
    assert extract(payload).features == extract(payload).features


def test_the_feature_version_is_reported() -> None:
    # Persisted on every assessment. Without it a score months old cannot be
    # attributed to the feature definitions that produced it.
    assert FEATURE_VERSION == "1.0.0"


# --------------------------------------------------------------------------- #
# Leakage
# --------------------------------------------------------------------------- #


def test_history_after_the_transaction_is_excluded() -> None:
    """The guard that keeps an evaluation meaningful.

    The API sends an account's recent history as of when it asked, which for a
    replayed or late-arriving transaction includes transactions from after the
    one being scored. Counting those is showing the model the future.
    """
    future = RecentTransaction(
        occurred_at=AT + timedelta(seconds=10),
        amount=money("999.00"),
        merchant_reference="MER-0002",
        device_reference="DEV-ffffffffffff",
        origin_country="SG",
        channel="CARD_PRESENT",
        type="PURCHASE",
    )
    payload = request(history=[future, earlier(30)])

    features = extract(payload).features

    # One prior transaction, not two, and the 999.00 from the future is in no sum.
    assert features["history_size"] == 1.0
    assert features["count_1m"] == 1.0
    assert features["sum_24h"] == 50.0


def test_a_transaction_at_the_same_instant_is_excluded() -> None:
    simultaneous = earlier(0)
    payload = request(history=[simultaneous])

    # Strictly before. A transaction at the same instant cannot be known to have
    # preceded this one, and including it would make the feature depend on how
    # the caller happened to break the tie.
    assert extract(payload).features["history_size"] == 0.0


def test_excluded_history_is_reported_rather_than_dropped_silently() -> None:
    future = RecentTransaction(
        occurred_at=AT + timedelta(minutes=5),
        amount=money("10.00"),
        merchant_reference="MER-0002",
        device_reference=None,
        origin_country="GB",
        channel="ATM",
        type="WITHDRAWAL",
    )

    warnings = extract(request(history=[future])).warnings

    assert any("leakage" in warning for warning in warnings)


# --------------------------------------------------------------------------- #
# Velocity and amount windows
# --------------------------------------------------------------------------- #


def test_counts_respect_their_windows() -> None:
    payload = request(
        history=[
            earlier(10),
            earlier(45),
            earlier(120),
            earlier(1_800),
            earlier(7_200),
        ]
    )

    features = extract(payload).features

    assert features["count_1m"] == 2.0
    assert features["count_5m"] == 3.0
    assert features["count_60m"] == 4.0


def test_sums_respect_their_windows_and_stay_decimal() -> None:
    payload = request(
        history=[
            earlier(60, amount="10.01"),
            earlier(1_800, amount="20.02"),
            earlier(7_200, amount="99.99"),
        ]
    )

    features = extract(payload).features

    # 10.01 + 20.02 exactly. A float accumulator gives 30.029999999999998 here,
    # which is the whole reason money is Decimal all the way through.
    assert features["sum_1h"] == pytest.approx(30.03)
    assert features["sum_24h"] == pytest.approx(130.02)


def test_a_window_boundary_is_half_open() -> None:
    # Exactly 60 seconds before is outside the one-minute window: the window is
    # (at - 60, at), so a boundary transaction lands in exactly one bucket rather
    # than being double-counted by two adjacent windows.
    assert extract(request(history=[earlier(60)])).features["count_1m"] == 0.0
    assert extract(request(history=[earlier(59)])).features["count_1m"] == 1.0


# --------------------------------------------------------------------------- #
# Defaults when there is no history
# --------------------------------------------------------------------------- #


def test_a_first_transaction_is_not_treated_as_alarming() -> None:
    """A new account is not a suspicious account.

    Every default here was chosen against the alternative that would have made an
    account's very first transaction its most alarming one.
    """
    features = extract(request(history=[])).features

    assert features["amount_to_account_mean_ratio"] == 1.0
    assert features["seconds_since_previous"] == 86_400.0
    assert features["is_country_change"] == 0.0
    assert features["is_channel_change"] == 0.0
    # New merchant is genuinely true, and is the one thing about a first
    # transaction that is worth saying.
    assert features["is_new_merchant"] == 1.0


def test_no_history_is_reported() -> None:
    assert any("no prior history" in w for w in extract(request(history=[])).warnings)


# --------------------------------------------------------------------------- #
# Individual features
# --------------------------------------------------------------------------- #


def test_amount_ratio_is_relative_to_this_account() -> None:
    payload = request(
        transaction=scored(amount="200.00"),
        history=[earlier(600, amount="10.00"), earlier(1_200, amount="30.00")],
    )

    # Mean of 20.00, so 200.00 is ten times it. Relative rather than absolute:
    # an absolute threshold would make the feature a property of the currency
    # rather than of the account.
    assert extract(payload).features["amount_to_account_mean_ratio"] == pytest.approx(10.0)


def test_a_new_device_is_new_only_where_a_device_means_something() -> None:
    known = earlier(600, device="DEV-0123456789ab")

    unseen = request(transaction=scored(device="DEV-ffffffffffff"), history=[known])
    assert extract(unseen).features["is_new_device"] == 1.0

    seen = request(transaction=scored(device="DEV-0123456789ab"), history=[known])
    assert extract(seen).features["is_new_device"] == 0.0


def test_a_cash_withdrawal_is_not_a_new_device() -> None:
    """An ATM has no device by nature, so a null there is a real answer.

    Treating it as "new device" would fire the feature on every cash withdrawal
    an account ever made, which makes it noise rather than signal.
    """
    payload = request(
        transaction=scored(device=None, channel="ATM"),
        history=[earlier(600, device="DEV-0123456789ab")],
    )

    assert extract(payload).features["is_new_device"] == 0.0


def test_country_and_channel_changes_compare_against_the_most_recent() -> None:
    payload = request(
        transaction=scored(country="SG", channel="CARD_PRESENT"),
        history=[earlier(600, country="GB", channel="CARD_NOT_PRESENT")],
    )

    features = extract(payload).features

    assert features["is_country_change"] == 1.0
    assert features["is_channel_change"] == 1.0


def test_rounded_amounts_are_flagged() -> None:
    assert (
        extract(request(transaction=scored(amount="500.00"))).features["is_rounded_amount"] == 1.0
    )
    assert (
        extract(request(transaction=scored(amount="137.42"))).features["is_rounded_amount"] == 0.0
    )


def test_off_hours_is_narrow() -> None:
    small_hours = scored(occurred_at=AT.replace(hour=3))
    evening = scored(occurred_at=AT.replace(hour=23))

    # 23:00 is ordinary evening spending. A wide window would make the feature
    # fire on it and stop meaning anything.
    assert extract(request(transaction=small_hours)).features["is_off_hours"] == 1.0
    assert extract(request(transaction=evening)).features["is_off_hours"] == 0.0


def test_balance_drain_is_a_fraction_and_survives_a_zero_balance() -> None:
    drain = request(transaction=scored(amount="800.00"), balance="1000.00")
    assert extract(drain).features["balance_drain_ratio"] == pytest.approx(0.8)

    # Not a large number - an undefined one. Returning something enormous would
    # make an overdrawn account permanently alarming.
    overdrawn = request(transaction=scored(amount="800.00"), balance="0")
    assert extract(overdrawn).features["balance_drain_ratio"] == 0.0


def test_fan_out_counts_distinct_merchants_within_the_hour() -> None:
    payload = request(
        history=[
            earlier(60, merchant="MER-0002"),
            earlier(120, merchant="MER-0003"),
            earlier(180, merchant="MER-0002"),
            earlier(7_200, merchant="MER-0009"),
        ]
    )

    # Three inside the hour, two of them distinct; the one two hours ago is out.
    assert extract(payload).features["distinct_merchants_1h"] == 2.0


def test_account_age_is_never_negative() -> None:
    # An account opened after the transaction it is being asked about is a data
    # defect somewhere upstream. A negative age would propagate it into the model
    # as a plausible-looking number.
    payload = request(opened_at=AT + timedelta(days=30))

    assert extract(payload).features["account_age_days"] == 0.0


# --------------------------------------------------------------------------- #
# Warnings
# --------------------------------------------------------------------------- #


def test_a_short_lookback_is_reported() -> None:
    """A feature named for 24 hours that received one hour is not a smaller number.

    It is a number that means something other than what its name says, and the
    caller has no way to know unless it is told.
    """
    warnings = extract(request(lookback=3_600, history=[earlier(60)])).warnings

    assert any("lookback window" in warning for warning in warnings)


def test_truncation_is_reported() -> None:
    warnings = extract(request(truncated=True, history=[earlier(60)])).warnings

    assert any("floors" in warning for warning in warnings)


def test_a_full_context_produces_no_warnings() -> None:
    # The clean case has to be clean, or a caller learns to ignore the field.
    payload = request(lookback=86_400, history=[earlier(60), earlier(600)])

    assert extract(payload).warnings == ()


def test_a_history_of_zero_amounts_does_not_divide_by_zero() -> None:
    """A refund-only history has a mean of zero, and a ratio against it is undefined.

    Unreachable through the current generator and entirely reachable through a
    real account, which is exactly the kind of case that is found in production
    rather than in a demo.
    """
    payload = request(
        transaction=scored(amount="200.00"),
        history=[earlier(600, amount="0"), earlier(1_200, amount="0")],
    )

    assert extract(payload).features["amount_to_account_mean_ratio"] == 1.0


def test_the_declared_column_order_is_the_one_extraction_produces() -> None:
    """:data:`FEATURE_NAMES` is declared, so something has to hold it to account.

    The loader asserts a manifest's recorded column order against this tuple
    before a model is used. If the tuple drifted from what :func:`extract`
    actually returns, that assertion would be checking a model against a list
    nothing produces — a check that passes and proves nothing.
    """
    extraction = extract(request())

    assert tuple(sorted(extraction.features)) == FEATURE_NAMES
    assert len(extraction.features) == len(FEATURE_NAMES)
