"""``GET /v1/model``: the metadata that makes a score reconstructible months later.

Every figure it publishes is asserted against the registry entry it came from
rather than against a literal. A test that hardcoded 0.83 would have to be edited
after every retrain, and the edit is where a number stops being measured and
starts being maintained.
"""

from __future__ import annotations

import json
from typing import Any

from fastapi.testclient import TestClient

from tests.serving.conftest import REGISTRY_ROOT

REQUIRED_FIELDS = {
    "modelVersion",
    "featureVersion",
    "algorithm",
    "trainedAt",
    "artifactSha256",
    "metrics",
}

REQUIRED_METRICS = {
    "precision",
    "recall",
    "f1",
    "averagePrecision",
    "falsePositiveRate",
    "operatingThreshold",
}


def entry() -> tuple[dict[str, Any], dict[str, Any]]:
    """The manifest and metrics of the one committed registry entry."""
    manifests = sorted(REGISTRY_ROOT.glob("*/*/manifest.json"))
    assert len(manifests) == 1, f"expected one registry entry, found {len(manifests)}"
    directory = manifests[0].parent
    return (
        json.loads(manifests[0].read_text(encoding="utf-8")),
        json.loads((directory / "metrics.json").read_text(encoding="utf-8")),
    )


def test_it_publishes_the_manifest_s_own_identity(client: TestClient) -> None:
    manifest, _ = entry()

    body = client.get("/v1/model").json()

    assert REQUIRED_FIELDS <= set(body)
    assert body["modelVersion"] == manifest["model_version"]
    assert body["featureVersion"] == manifest["feature_version"]
    assert body["trainedAt"] == manifest["trained_at"]
    assert body["datasetFingerprint"] == manifest["dataset_sha256"]


def test_the_algorithm_is_the_phrase_the_contract_asked_for(client: TestClient) -> None:
    """The contract wants "what it is, in one phrase" and gives
    ``logistic-regression`` as its example. The manifest's ``algorithm`` field
    holds the candidate's full sentence, which belongs in the model card."""
    manifest, _ = entry()

    assert client.get("/v1/model").json()["algorithm"] == manifest["model_name"]


def test_the_checksum_is_the_one_that_was_verified_at_load(client: TestClient) -> None:
    """Not a decoration. It is what makes "this service loads only artifacts its
    own training pipeline produced" a check rather than a convention."""
    manifest, _ = entry()

    assert client.get("/v1/model").json()["artifactSha256"] == manifest["artifact_sha256"]


def test_the_metrics_are_the_selected_model_s_holdout_figures(client: TestClient) -> None:
    _, metrics = entry()
    holdout = metrics["candidates"][metrics["selected"]]["holdout"]

    published = client.get("/v1/model").json()["metrics"]

    assert REQUIRED_METRICS <= set(published)
    assert published["precision"] == holdout["precision"]
    assert published["recall"] == holdout["recall"]
    assert published["f1"] == holdout["f1"]
    assert published["averagePrecision"] == holdout["pr_auc"]
    assert published["falsePositiveRate"] == holdout["false_positive_rate"]
    assert published["operatingThreshold"] == holdout["threshold"]
    assert published["alertVolumeAtThreshold"] == holdout["alert_count"]


def test_accuracy_is_not_published(client: TestClient) -> None:
    """Deliberately absent, here and in the contract. Under this class imbalance a
    model that answers "not suspicious" to everything scores well on accuracy and
    is worthless; reporting it at all invites quoting it."""
    published = client.get("/v1/model").json()["metrics"]

    assert "accuracy" not in published


def test_the_operating_point_is_on_the_contract_s_scale(client: TestClient) -> None:
    """A recommendation, not the threshold that ran — the API's alerting policy is
    a separate versioned object (ADR-0008 §4)."""
    published = client.get("/v1/model").json()["metrics"]

    assert 0.0 <= published["operatingThreshold"] <= 100.0


def test_without_a_model_it_is_a_503(modelless_client: TestClient) -> None:
    response = modelless_client.get("/v1/model")

    assert response.status_code == 503
    assert response.headers["content-type"].startswith("application/problem+json")
