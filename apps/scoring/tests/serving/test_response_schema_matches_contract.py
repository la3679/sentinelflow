"""Keeps the response models and the contract from drifting.

The request side has had this since the schemas were written; the response side
needs it for the same reason and one more. A request model that drifts produces a
422 the caller can see. A **response** model that drifts produces a field the
caller's generated types do not have, which is silently dropped — and a dropped
``featureVersion`` is a score nobody can attribute, discovered months later.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

import pytest
import yaml

from sentinelflow_scoring.serving.schema import (
    MAX_REASONS,
    MAX_WARNING_LENGTH,
    MAX_WARNINGS,
    Liveness,
    ModelInfo,
    ModelMetrics,
    Readiness,
    ReasonContribution,
    ScoreResponse,
)

#: tests/serving/ -> tests/ -> scoring/ -> apps/ -> the repository root.
CONTRACT = (
    Path(__file__).resolve().parents[4] / "contracts" / "openapi" / "sentinelflow-scoring.yaml"
)

MODELS = [
    (Liveness, "Liveness"),
    (Readiness, "Readiness"),
    (ReasonContribution, "ReasonContribution"),
    (ScoreResponse, "ScoreResponse"),
    (ModelMetrics, "ModelMetrics"),
    (ModelInfo, "ModelInfo"),
]


@pytest.fixture(scope="module")
def schemas() -> dict[str, Any]:
    """The contract's component schemas.

    Deliberately not guarded by a skip: a skip that fires because of a defect is
    indistinguishable from one that fires because of a legitimately absent file,
    and there is no legitimate absence. ``apps/scoring/Dockerfile`` copies only
    ``src/``, so the image build never runs this.
    """
    assert CONTRACT.exists(), f"contract not found at {CONTRACT}"
    document = yaml.safe_load(CONTRACT.read_text(encoding="utf-8"))
    result: dict[str, Any] = document["components"]["schemas"]
    return result


def wire_names(model: type) -> set[str]:
    """The field names as they appear on the wire, not as Python spells them."""
    return {field.alias or name for name, field in model.model_fields.items()}  # type: ignore[attr-defined]


@pytest.mark.parametrize(("model", "schema_name"), MODELS)
def test_fields_match_the_contract(schemas: dict[str, Any], model: type, schema_name: str) -> None:
    assert wire_names(model) == set(schemas[schema_name]["properties"])


@pytest.mark.parametrize(("model", "schema_name"), MODELS)
def test_every_required_contract_field_is_required_here(
    schemas: dict[str, Any], model: type, schema_name: str
) -> None:
    """A field the contract requires must not be optional here.

    On a response this is the direction that bites: an optional field is one this
    service may omit, and a caller that binds to the contract has every right to
    assume it is present.
    """
    required = set(schemas[schema_name].get("required", []))
    optional_here = {
        field.alias or name
        for name, field in model.model_fields.items()  # type: ignore[attr-defined]
        if not field.is_required() and field.default is None
    }
    assert not (required & optional_here), (
        f"{schema_name}: {required & optional_here} required by the contract, optional here"
    )


def test_the_reason_cap_is_the_contract_s(schemas: dict[str, Any]) -> None:
    assert MAX_REASONS == schemas["ScoreResponse"]["properties"]["reasons"]["maxItems"]


def test_the_warning_caps_are_the_contract_s(schemas: dict[str, Any]) -> None:
    warnings = schemas["ScoreResponse"]["properties"]["warnings"]

    assert MAX_WARNINGS == warnings["maxItems"]
    assert MAX_WARNING_LENGTH == warnings["items"]["maxLength"]


def test_the_score_range_is_the_contract_s(schemas: dict[str, Any]) -> None:
    """The one number a caller thresholds on. If these two disagree, a score the
    contract calls impossible is one this service will happily return."""
    contract_score = schemas["ScoreResponse"]["properties"]["modelScore"]
    field = ScoreResponse.model_fields["model_score"]
    bounds = {
        type(item).__name__: getattr(item, "ge", getattr(item, "le", None))
        for item in field.metadata
    }

    assert contract_score["minimum"] == bounds.get("Ge")
    assert contract_score["maximum"] == bounds.get("Le")


def test_the_reason_code_pattern_is_the_contract_s(schemas: dict[str, Any]) -> None:
    """A code is never renamed once emitted — a renamed code silently breaks every
    historical query — so the shape both sides accept has to be one shape."""
    contract_pattern = schemas["ReasonContribution"]["properties"]["code"]["pattern"]
    field = ReasonContribution.model_fields["code"]
    patterns = [getattr(item, "pattern", None) for item in field.metadata]

    assert contract_pattern in patterns
