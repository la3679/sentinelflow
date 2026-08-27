"""A small labelled export, built the way the real one is.

Written as JSON on disk rather than as a :class:`TrainingData` built in memory,
so the loader is exercised end to end and the fixture cannot drift from the
format ``apps/api`` actually writes. The field names here are the contract's, and
if they stop matching, these tests fail — which is the point.
"""

from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

import pytest

WINDOW_START = datetime(2026, 8, 12, tzinfo=UTC)
OPENED = datetime(2025, 1, 1, tzinfo=UTC)

#: Matches the exporter's own negative label.
NORMAL = "NORMAL"
SHAPE = "VELOCITY_BURST"


def _amount(value: str) -> dict[str, str]:
    return {"value": value, "currency": "GBP"}


def _example(
    *,
    account: int,
    index: int,
    at: datetime,
    label: str,
    rule_score: str,
    amount: str = "40.0000",
    history: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    return {
        "transaction": {
            "transactionId": f"01a04000-0000-7000-8000-{account:06d}{index:06d}",
            "accountReference": f"ACC-{account:06d}",
            "merchantReference": f"MER-{(index % 9) + 1:04d}",
            "merchantCategoryCode": "5411",
            "type": "PURCHASE",
            "channel": "CARD_NOT_PRESENT",
            "amount": _amount(amount),
            "originCountry": "GB",
            "deviceReference": "DEV-0123456789ab",
            "occurredAt": at.isoformat().replace("+00:00", "Z"),
        },
        "accountContext": {
            "contextVersion": 1,
            "lookbackWindowSeconds": 86400,
            "accountOpenedAt": OPENED.isoformat().replace("+00:00", "Z"),
            "currentBalance": _amount("5000.0000"),
            "recentTransactions": history or [],
            "truncated": False,
        },
        "label": label,
        # Written by the fixture rather than computed, for the same reason the
        # rest of this file is JSON on disk: what the loader reads has to be the
        # shape `apps/api` writes, and the API's ruleset is not runnable from
        # here. The distribution below is what matters — see build_export.
        "ruleScore": rule_score,
    }


def _history(at: datetime, count: int) -> list[dict[str, Any]]:
    """``count`` transactions in the minute before ``at``, newest first.

    Enough to move the velocity features, which is what makes a planted row
    separable from a background one — a fixture where both classes look identical
    would test the plumbing and nothing else.
    """
    return [
        {
            "occurredAt": (at - timedelta(seconds=10 * (position + 1)))
            .isoformat()
            .replace("+00:00", "Z"),
            "amount": _amount("35.0000"),
            "merchantReference": f"MER-{position + 1:04d}",
            "deviceReference": "DEV-0123456789ab",
            "originCountry": "GB",
            "channel": "CARD_NOT_PRESENT",
            "type": "PURCHASE",
        }
        for position in range(count)
    ]


def build_export(
    directory: Path,
    *,
    accounts: int = 80,
    per_account: int = 16,
    positive_accounts: int = 40,
) -> Path:
    """Writes ``dataset.jsonl`` and ``manifest.json``.

    Sized so the holdout clears ``MINIMUM_HOLDOUT_POSITIVES``. A smaller
    fixture was tried first and the pipeline refused to promote anything from
    it — correctly, which is why the floor exists, and which made the fixture
    rather than the code the thing to change.

    **Positives are deliberately below the rules baseline's own thresholds** —
    three prior transactions rather than four, an amount ratio near four
    rather than above five. A fixture whose positives the rules catch outright
    makes the baseline unbeatable and every model correctly fails to qualify,
    which tests the selection rule and nothing else. These are separable by a
    model and invisible to the rules, which is the case worth exercising.

    The ``ruleScore`` on each line says the same thing in the field the trainer
    actually reads: the ruleset catches **one planted row in six** and fires
    weakly on some background traffic, so the baseline is neither degenerate nor
    unbeatable. A fixture where every rule score were zero would give the
    baseline a PR-AUC of exactly the base rate and a threshold of zero, which is
    a comparison against nothing rather than against a floor.

    Positives are spread over ``positive_accounts`` accounts and **across the
    whole window**, not concentrated at one end. Both halves need them: the
    holdout so recall is defined, and the training set so a supervised model has
    something to learn. A first version of this fixture placed every positive
    late and every split test failed on an empty training class — the guard
    working, and the fixture describing a world the generator does not produce.
    """
    directory.mkdir(parents=True, exist_ok=True)
    lines: list[str] = []

    for account in range(accounts):
        for index in range(per_account):
            at = WINDOW_START + timedelta(hours=6 * index)
            planted = account < positive_accounts and index % 3 == 0

            if planted:
                rule_score = "40.00" if index % 6 == 0 else "10.00"
            else:
                rule_score = "15.00" if index % 5 == 0 else "0.00"

            lines.append(
                json.dumps(
                    _example(
                        account=account,
                        index=index,
                        at=at,
                        label=SHAPE if planted else NORMAL,
                        rule_score=rule_score,
                        amount="150.0000" if planted else "40.0000",
                        history=_history(at, 3 if planted else 0),
                    )
                )
            )

    (directory / "dataset.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")
    (directory / "manifest.json").write_text(
        json.dumps(
            {
                "generatorVersion": "1.1.0",
                "seed": 20260826,
                "profile": "TEST",
                "exportedAt": "2026-08-26T12:00:00Z",
                "contextVersion": 1,
                "lookbackWindowSeconds": 86400,
                "exported": len(lines),
                "generated": len(lines),
                "distribution": {NORMAL: 0, SHAPE: 0},
                "negativeLabel": NORMAL,
                "rulesetVersion": "1.0.0",
                "scenarioChecksum": "0" * 64,
                "datasetSha256": "1" * 64,
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    return directory


@pytest.fixture
def export(tmp_path: Path) -> Path:
    return build_export(tmp_path / "training")
