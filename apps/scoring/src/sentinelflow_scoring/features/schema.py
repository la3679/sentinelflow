"""Request models for ``POST /v1/score``.

Field-for-field with ``contracts/openapi/sentinelflow-scoring.yaml``, which is
authoritative: where these and the contract disagree, the contract is right and
this is a defect. ``test_schema_matches_contract.py`` asserts they have not
drifted, because a YAML file is data as far as the type checker is concerned and
nothing else in a Python build notices when a field is added to one side only.

**``extra="forbid"``, deliberately, and unlike an event consumer.** ADR-0006 §3
splits this in two. An *event* consumer ignores fields it does not know, because
a producer that added an optional field is doing something the compatibility
policy explicitly allows. This is not an event: it is a synchronous request
between two services in one repository, where the contract declares
``additionalProperties: false`` and a caller sending an unknown field has a bug.
Rejecting it produces a 422 naming the field instead of a score computed from a
request nobody meant to send.

**Money arrives as a string and is parsed to ``Decimal`` here.** ADR-0007: a JSON
number is a ``double`` to every JavaScript consumer and is rounded before
application code sees it. Binding to ``float`` on this side would throw that
away at the last possible moment.
"""

from __future__ import annotations

from datetime import datetime
from decimal import Decimal
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

MONEY_PATTERN = r"^-?(0|[1-9][0-9]{0,14})(\.[0-9]{1,4})?$"
ACCOUNT_REFERENCE_PATTERN = r"^ACC-[0-9]{6}$"
MERCHANT_REFERENCE_PATTERN = r"^MER-[0-9]{4}$"
DEVICE_REFERENCE_PATTERN = r"^DEV-[0-9a-f]{12}$"
COUNTRY_PATTERN = r"^[A-Z]{2}$"
CURRENCY_PATTERN = r"^[A-Z]{3}$"
CATEGORY_CODE_PATTERN = r"^[0-9]{4}$"

TransactionType = Literal["PURCHASE", "REFUND", "TRANSFER", "WITHDRAWAL", "DEPOSIT"]
TransactionChannel = Literal[
    "CARD_PRESENT", "CARD_NOT_PRESENT", "ONLINE_TRANSFER", "ATM", "DIRECT_DEBIT"
]

#: The contract's cap on ``recentTransactions``. A request that grew with an
#: account's history would be a denial-of-service primitive, and a service that
#: accepted an unbounded one would be the vulnerability rather than the victim.
MAX_RECENT_TRANSACTIONS = 200


def to_camel(name: str) -> str:
    """``account_context`` -> ``accountContext``.

    A named function rather than an inline lambda because the response models in
    :mod:`sentinelflow_scoring.serving.schema` need the same rule, and two copies
    of a naming convention is how one side of a contract starts spelling a field
    differently from the other.
    """
    parts = name.split("_")
    return parts[0] + "".join(part.capitalize() for part in parts[1:])


class _Strict(BaseModel):
    """Shared configuration. Camel-case on the wire, snake_case in Python."""

    model_config = ConfigDict(
        extra="forbid",
        frozen=True,
        populate_by_name=True,
        alias_generator=to_camel,
    )


class Amount(_Strict):
    """A monetary amount and its currency. Never one without the other."""

    value: Annotated[str, Field(pattern=MONEY_PATTERN)]
    currency: Annotated[str, Field(pattern=CURRENCY_PATTERN)]

    @property
    def decimal(self) -> Decimal:
        """The value as a ``Decimal``. Never a ``float``: see ADR-0007."""
        return Decimal(self.value)


class RecentTransaction(_Strict):
    """One earlier transaction on the same account.

    Deliberately thin. Enough to compute a velocity, an amount ratio or a
    "new merchant" indicator, and nothing more — a richer shape would invite
    features that the API happens to have lying around rather than features the
    model needs.
    """

    occurred_at: datetime
    amount: Amount
    merchant_reference: Annotated[str, Field(pattern=MERCHANT_REFERENCE_PATTERN)]
    device_reference: Annotated[str | None, Field(pattern=DEVICE_REFERENCE_PATTERN)]
    origin_country: Annotated[str, Field(pattern=COUNTRY_PATTERN)]
    channel: TransactionChannel
    type: TransactionType


class TransactionToScore(_Strict):
    """The transaction being scored."""

    transaction_id: str
    account_reference: Annotated[str, Field(pattern=ACCOUNT_REFERENCE_PATTERN)]
    merchant_reference: Annotated[str, Field(pattern=MERCHANT_REFERENCE_PATTERN)]
    merchant_category_code: Annotated[str, Field(pattern=CATEGORY_CODE_PATTERN)]
    type: TransactionType
    channel: TransactionChannel
    amount: Amount
    origin_country: Annotated[str, Field(pattern=COUNTRY_PATTERN)]
    device_reference: Annotated[str | None, Field(pattern=DEVICE_REFERENCE_PATTERN)]
    occurred_at: datetime


class AccountContext(_Strict):
    """The account history this service needs and cannot look up.

    ``lookback_window_seconds`` and ``truncated`` exist because their absence
    would be a silent wrong answer rather than a missing one: a feature defined
    over 24 hours that only received an hour of history, or a count taken from a
    list that hit its cap, is not a smaller number — it is a number that means
    something other than what its name says.
    """

    context_version: Annotated[int, Field(ge=1)]
    lookback_window_seconds: Annotated[int, Field(ge=1)]
    account_opened_at: datetime
    current_balance: Amount
    recent_transactions: Annotated[
        list[RecentTransaction], Field(max_length=MAX_RECENT_TRANSACTIONS)
    ]
    truncated: bool = False

    @field_validator("recent_transactions")
    @classmethod
    def _newest_first(cls, value: list[RecentTransaction]) -> list[RecentTransaction]:
        """The contract says newest first, and every window walk here relies on it.

        Validated rather than sorted. Silently re-sorting would hide a caller
        sending an order it did not mean to, and the first symptom would be a
        velocity feature that is quietly wrong on some accounts and not others.
        """
        times = [item.occurred_at for item in value]
        if times != sorted(times, reverse=True):
            raise ValueError("recentTransactions must be ordered newest first")
        return value


class ScoreRequest(_Strict):
    """What ``POST /v1/score`` accepts."""

    transaction: TransactionToScore
    account_context: AccountContext
