"""Keeps the request models and the contract from drifting.

``contracts/openapi/sentinelflow-scoring.yaml`` is authoritative, which only
means something if something checks it. A YAML file is data as far as mypy is
concerned, so nothing else in this build notices when a field is added to one
side and not the other — and the symptom is a 422 in production, or worse, a
field silently ignored.

The contract declares ``additionalProperties: false`` on every request object,
which makes a field present here and absent there not an addition but a request
every conforming server must reject.

These tests read a file above the module. That is safe here in a way it was not
on the Java side: ``apps/scoring/Dockerfile`` copies only ``src/``, so the image
build never runs them, and CI runs the suite from the repository checkout where
``contracts/`` exists. If tests are ever added to the image build, this file has
to move or become an integration test.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

import pytest
import yaml

from sentinelflow_scoring.features.schema import (
    MAX_RECENT_TRANSACTIONS,
    AccountContext,
    Amount,
    RecentTransaction,
    ScoreRequest,
    TransactionToScore,
)

#: tests/features/ -> tests/ -> scoring/ -> apps/ -> the repository root.
CONTRACT = (
    Path(__file__).resolve().parents[4] / "contracts" / "openapi" / "sentinelflow-scoring.yaml"
)


@pytest.fixture(scope="module")
def schemas() -> dict[str, Any]:
    """The contract's component schemas.

    **Deliberately not guarded by a skip.** The first version of this file
    skipped when the path did not resolve, and the path was wrong by one level —
    so twelve tests reported as skipped and the suite passed. A skip that fires
    because of a defect is indistinguishable from a skip that fires because of a
    legitimately absent file, and this project's whole position on evidence is
    that a green run has to mean something. There is no legitimate absence:
    ``apps/scoring/Dockerfile`` copies only ``src/``, so the image build never
    runs this, and CI runs it from the repository checkout.
    """
    assert CONTRACT.exists(), f"contract not found at {CONTRACT}"
    document = yaml.safe_load(CONTRACT.read_text(encoding="utf-8"))
    result: dict[str, Any] = document["components"]["schemas"]
    return result


def wire_names(model: type) -> set[str]:
    """The field names as they appear on the wire, not as Python spells them."""
    return {field.alias or name for name, field in model.model_fields.items()}  # type: ignore[attr-defined]


@pytest.mark.parametrize(
    ("model", "schema_name"),
    [
        (Amount, "Amount"),
        (RecentTransaction, "RecentTransaction"),
        (TransactionToScore, "TransactionToScore"),
        (AccountContext, "AccountContext"),
        (ScoreRequest, "ScoreRequest"),
    ],
)
def test_fields_match_the_contract(schemas: dict[str, Any], model: type, schema_name: str) -> None:
    assert wire_names(model) == set(schemas[schema_name]["properties"])


@pytest.mark.parametrize(
    ("model", "schema_name"),
    [
        (Amount, "Amount"),
        (RecentTransaction, "RecentTransaction"),
        (TransactionToScore, "TransactionToScore"),
        (AccountContext, "AccountContext"),
        (ScoreRequest, "ScoreRequest"),
    ],
)
def test_unknown_fields_are_rejected(
    schemas: dict[str, Any], model: type, schema_name: str
) -> None:
    """Both sides agree that an unknown field is an error, not something to ignore.

    The contract says ``additionalProperties: false``; the models say
    ``extra="forbid"``. Asserting both together is what stops one of them being
    relaxed on its own.
    """
    assert schemas[schema_name]["additionalProperties"] is False
    assert model.model_config["extra"] == "forbid"  # type: ignore[attr-defined]


def test_the_recent_transaction_cap_is_the_contract_s(schemas: dict[str, Any]) -> None:
    """Two numbers in two files that have to be the same one.

    If the schema's cap is higher, the service rejects requests the contract
    permits. If it is lower, the service accepts an unbounded-ish request the
    contract said it would not — which is the direction that matters.
    """
    contract_cap = schemas["AccountContext"]["properties"]["recentTransactions"]["maxItems"]

    assert MAX_RECENT_TRANSACTIONS == contract_cap


def test_every_required_contract_field_is_required_here(schemas: dict[str, Any]) -> None:
    """A field the contract requires must not be optional in the model.

    The reverse is allowed — a model may be stricter — but a model that treats a
    required field as optional silently accepts a request the contract forbids,
    and the missing value then reaches a feature as a default nobody chose.
    """
    for model, schema_name in (
        (Amount, "Amount"),
        (RecentTransaction, "RecentTransaction"),
        (TransactionToScore, "TransactionToScore"),
        (AccountContext, "AccountContext"),
        (ScoreRequest, "ScoreRequest"),
    ):
        required = set(schemas[schema_name].get("required", []))
        optional_here = {
            field.alias or name
            for name, field in model.model_fields.items()
            if not field.is_required()
        }
        assert not (required & optional_here), (
            f"{schema_name}: {required & optional_here} required by the contract, optional here"
        )


def test_history_in_the_wrong_order_is_rejected_rather_than_sorted() -> None:
    """Silently re-sorting would hide a caller sending an order it did not mean.

    Every window walk in the extractor relies on newest-first, so a caller that
    sends oldest-first has a bug — and the symptom, if this were tolerated, would
    be a velocity feature quietly wrong on some accounts and not others.
    """
    from datetime import UTC, datetime, timedelta

    import pydantic

    base = datetime(2026, 8, 26, 14, 30, tzinfo=UTC)

    def at(offset: int) -> RecentTransaction:
        return RecentTransaction(
            occurred_at=base - timedelta(seconds=offset),
            amount=Amount(value="10.00", currency="GBP"),
            merchant_reference="MER-0001",
            device_reference=None,
            origin_country="GB",
            channel="ATM",
            type="WITHDRAWAL",
        )

    with pytest.raises(pydantic.ValidationError, match="newest first"):
        AccountContext(
            context_version=1,
            lookback_window_seconds=86_400,
            account_opened_at=base - timedelta(days=100),
            current_balance=Amount(value="100.00", currency="GBP"),
            recent_transactions=[at(600), at(60)],
        )
