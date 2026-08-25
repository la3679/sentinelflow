"""Smoke tests for the service's operational surface.

These exercise the running ASGI application through HTTP, not the handler
functions directly, so what is asserted is what an operator's probe would
actually receive.
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from sentinelflow_scoring import __version__
from sentinelflow_scoring.app import create_app
from sentinelflow_scoring.config import Settings


@pytest.fixture
def client() -> TestClient:
    """A client bound to an app built from explicit settings.

    Settings are passed in rather than read from the environment so the test
    asserts against a known configuration and does not depend on what happens
    to be exported in the shell that runs it.
    """
    settings = Settings(git_sha="0000000000000000000000000000000000000000")
    return TestClient(create_app(settings))


def test_liveness_reports_up(client: TestClient) -> None:
    response = client.get("/health/live")

    assert response.status_code == 200
    assert response.json() == {"status": "UP"}


def test_readiness_is_separate_from_liveness(client: TestClient) -> None:
    response = client.get("/health/ready")

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "UP"
    # No model exists before Phase 4. Readiness reports that rather than
    # claiming a capability the service does not have.
    assert body["model_loaded"] is False


def test_info_reports_build_identity(client: TestClient) -> None:
    response = client.get("/info")

    assert response.status_code == 200
    assert response.json() == {
        "name": "sentinelflow-scoring",
        "version": __version__,
        "git_sha": "0000000000000000000000000000000000000000",
    }


def test_metrics_are_scrapable(client: TestClient) -> None:
    response = client.get("/metrics")

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/plain")
    # The default registry carries the platform collectors, so a real scrape is
    # never empty. An empty body would mean the endpoint is serving a registry
    # nothing is attached to.
    assert "python_info" in response.text


def test_openapi_document_is_served(client: TestClient) -> None:
    response = client.get("/openapi.json")

    assert response.status_code == 200
    document = response.json()
    assert document["info"]["title"] == "SentinelFlow scoring service"
    assert "/health/live" in document["paths"]
    # /metrics is excluded from the schema: it is an operational endpoint, not
    # part of the service contract.
    assert "/metrics" not in document["paths"]


def test_settings_reject_an_unknown_variable() -> None:
    """A typo in a configuration key must fail loudly, not be ignored."""
    with pytest.raises(ValueError, match="not_a_real_setting"):
        Settings(not_a_real_setting="x")  # type: ignore[call-arg]
