"""Fixtures for the request path.

**These tests run against the committed registry entry, not a stub.** ADR-0010 §6
commits the artifact so a demo can score without a training run first, which means
the thing serving requests in a demo is exactly the thing under test here. A
hand-rolled fake estimator would pass while the real entry failed its checksum,
its feature version, or its column order — the three failures that matter.
"""

from __future__ import annotations

from collections.abc import Iterator
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

import pytest
from fastapi.testclient import TestClient

from sentinelflow_scoring.app import create_app
from sentinelflow_scoring.config import Settings

#: tests/serving/ -> tests/ -> apps/scoring/
REGISTRY_ROOT = Path(__file__).resolve().parents[2] / "models"

#: A fixed instant, so every feature computed from it is the same on every run.
#: 03:10 UTC is inside the extractor's off-hours window, which is deliberate: a
#: request that exercises an indicator is worth more than one that does not.
SCORED_AT = datetime(2026, 8, 26, 3, 10, tzinfo=UTC)


def settings_for(root: Path) -> Settings:
    """Settings pointing at a registry root, with nothing read from the environment."""
    return Settings(
        git_sha="0000000000000000000000000000000000000000",
        models_root=root,
    )


@pytest.fixture(scope="module")
def client() -> Iterator[TestClient]:
    """A client over the committed registry entry."""
    assert REGISTRY_ROOT.is_dir(), f"registry not found at {REGISTRY_ROOT}"
    with TestClient(create_app(settings_for(REGISTRY_ROOT))) as running:
        yield running


@pytest.fixture
def modelless_client(tmp_path: Path) -> Iterator[TestClient]:
    """A client over an empty registry.

    Not a mock and not a patched flag: an empty directory is exactly what a clone
    that has never run ``make train`` looks like, and the 503 path is the one the
    API's degraded assessment depends on.
    """
    empty = tmp_path / "models"
    empty.mkdir()
    with TestClient(create_app(settings_for(empty))) as running:
        yield running


def recent(
    seconds_ago: int,
    *,
    value: str = "42.50",
    merchant: str = "MER-0001",
    device: str | None = "DEV-0123456789ab",
    country: str = "GB",
    channel: str = "CARD_NOT_PRESENT",
) -> dict[str, Any]:
    """One earlier transaction on the account, positioned relative to `SCORED_AT`."""
    return {
        "occurredAt": _iso(SCORED_AT - timedelta(seconds=seconds_ago)),
        "amount": {"value": value, "currency": "GBP"},
        "merchantReference": merchant,
        "deviceReference": device,
        "originCountry": country,
        "channel": channel,
        "type": "PURCHASE",
    }


def payload(
    transaction: dict[str, Any] | None = None,
    account_context: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """A valid request, in the contract's camel case.

    Written as a dict rather than built from the Pydantic models, so what the test
    sends is what a Java caller would send. Building it from the models would make
    a serialisation defect invisible to every test that used it.
    """
    body: dict[str, Any] = {
        "transaction": {
            "transactionId": "0198f0a1-2b3c-7d4e-8f90-1a2b3c4d5e6f",
            "accountReference": "ACC-000123",
            "merchantReference": "MER-0042",
            "merchantCategoryCode": "5411",
            "type": "PURCHASE",
            "channel": "CARD_NOT_PRESENT",
            "amount": {"value": "1200.00", "currency": "GBP"},
            "originCountry": "FR",
            "deviceReference": "DEV-fedcba987654",
            "occurredAt": _iso(SCORED_AT),
        },
        "accountContext": {
            "contextVersion": 1,
            "lookbackWindowSeconds": 86_400,
            "accountOpenedAt": _iso(SCORED_AT - timedelta(days=400)),
            "currentBalance": {"value": "2500.0000", "currency": "GBP"},
            "recentTransactions": [
                recent(45),
                recent(200, value="18.00", merchant="MER-0002"),
                recent(3_000, value="61.25", merchant="MER-0003"),
                recent(50_000, value="9.99", merchant="MER-0001"),
            ],
            "truncated": False,
        },
    }
    if transaction is not None:
        body["transaction"] = transaction
    if account_context is not None:
        body["accountContext"] = account_context
    return body


def _iso(moment: datetime) -> str:
    return moment.isoformat().replace("+00:00", "Z")
