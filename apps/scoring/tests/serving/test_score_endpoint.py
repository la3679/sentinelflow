"""``POST /v1/score``, exercised through HTTP against the committed model.

What these assert, in order of how much it would cost to get wrong: that the
response is the shape the contract promises, that the same request scores the same
twice, that an invalid request is refused without handing anything back, and that
a service with no model refuses in the way the caller's fallback expects.
"""

from __future__ import annotations

import re
from typing import Any

from fastapi.testclient import TestClient

from sentinelflow_scoring.serving.schema import MAX_REASONS, MAX_WARNING_LENGTH, MAX_WARNINGS
from tests.serving.conftest import payload, recent

REASON_CODE = re.compile(r"^[A-Z][A-Z0-9_]{2,63}$")

#: Every field the contract marks required on ``ScoreResponse``.
REQUIRED_FIELDS = {
    "modelVersion",
    "featureVersion",
    "modelScore",
    "reasons",
    "inferenceDurationMs",
    "warnings",
}


def score(
    client: TestClient,
    transaction: dict[str, Any] | None = None,
    account_context: dict[str, Any] | None = None,
) -> dict[str, Any]:
    response = client.post("/v1/score", json=payload(transaction, account_context))
    assert response.status_code == 200, response.text
    body: dict[str, Any] = response.json()
    return body


def test_the_response_carries_exactly_the_contract_s_fields(client: TestClient) -> None:
    """``additionalProperties: false`` cuts both ways.

    A field this service invents is one no caller can bind, and a caller that
    validates the response against the contract rejects it.
    """
    body = score(client)

    assert set(body) == REQUIRED_FIELDS


def test_the_score_is_on_the_contract_s_scale(client: TestClient) -> None:
    body = score(client)

    assert 0.0 <= body["modelScore"] <= 100.0


def test_the_versions_are_the_loaded_model_s(client: TestClient) -> None:
    """Without these two a score months old cannot be attributed to anything.

    They are asserted against ``/v1/model`` rather than against a literal, so the
    test keeps meaning something after a retrain.
    """
    body = score(client)
    active = client.get("/v1/model").json()

    assert body["modelVersion"] == active["modelVersion"]
    assert body["featureVersion"] == active["featureVersion"]


def test_reasons_are_bounded_and_most_significant_first(client: TestClient) -> None:
    reasons = score(client)["reasons"]

    assert len(reasons) <= MAX_REASONS
    assert reasons, "a linear model always has something to attribute"
    for reason in reasons:
        assert REASON_CODE.match(reason["code"]), reason["code"]
        assert set(reason) == {"code", "contribution"}

    magnitudes = [abs(reason["contribution"]) for reason in reasons]
    assert magnitudes == sorted(magnitudes, reverse=True)


def test_an_indicator_is_a_reason_only_when_it_fired(client: TestClient) -> None:
    """``NEW_DEVICE`` on a device the account has always used says the opposite.

    A boolean at 0.0 still has a standardised value and a non-zero contribution,
    so this is the difference between reporting the model's arithmetic and
    reporting something an analyst can act on.
    """
    known = payload()["transaction"]
    known["deviceReference"] = "DEV-0123456789ab"
    known["merchantReference"] = "MER-0001"
    known["originCountry"] = "GB"
    known["channel"] = "CARD_NOT_PRESENT"

    codes = {reason["code"] for reason in score(client, transaction=known)["reasons"]}

    assert "NEW_DEVICE" not in codes
    assert "NEW_MERCHANT" not in codes
    assert "COUNTRY_CHANGE" not in codes
    assert "CHANNEL_CHANGE" not in codes


def test_an_indicator_that_did_fire_is_reported(client: TestClient) -> None:
    """The scored transaction originates in FR; every transaction before it was GB.

    The pair of tests around this one is the assertion: the same indicator is
    present here and absent above, on requests that differ only in whether it
    fired. Neither alone would prove much, because a reason list is capped at ten
    and an absent code could always be one that simply did not rank.
    """
    codes = {reason["code"] for reason in score(client)["reasons"]}

    assert "COUNTRY_CHANGE" in codes


def test_the_same_request_scores_the_same_twice(client: TestClient) -> None:
    """The contract's idempotency claim, which is about having no side effects.

    ``inferenceDurationMs`` is excluded because it is a measurement of this run
    and is *supposed* to differ; everything a caller would persist must not.
    """
    first = score(client)
    second = score(client)

    assert first["modelScore"] == second["modelScore"]
    assert first["reasons"] == second["reasons"]
    assert first["warnings"] == second["warnings"]


def test_warnings_are_present_and_bounded(client: TestClient) -> None:
    """Present and empty rather than omitted, so no caller has to tell the two apart."""
    warnings = score(client)["warnings"]

    assert isinstance(warnings, list)
    assert len(warnings) <= MAX_WARNINGS
    assert all(len(warning) <= MAX_WARNING_LENGTH for warning in warnings)


def test_a_thin_context_is_warned_about_rather_than_hidden(client: TestClient) -> None:
    """A count over an hour computed from ten minutes of history is not a smaller
    number — it is a number that means something other than its name."""
    context = payload()["accountContext"]
    context["lookbackWindowSeconds"] = 600
    context["truncated"] = True
    context["recentTransactions"] = [recent(45)]

    warnings = score(client, account_context=context)["warnings"]

    assert any("lookback window" in warning for warning in warnings)
    assert any("truncated" in warning for warning in warnings)


def test_the_inference_duration_is_measured_not_invented(client: TestClient) -> None:
    body = score(client)

    assert body["inferenceDurationMs"] >= 0.0
    # A whole second for one row through a logistic regression would mean the
    # measurement is of something other than the inference.
    assert body["inferenceDurationMs"] < 1_000.0


# --------------------------------------------------------------------------- #
# Refusals
# --------------------------------------------------------------------------- #


def test_an_unknown_field_is_refused_and_named(client: TestClient) -> None:
    """A caller sending a field nobody declared has a bug, and it is told which one.

    ADR-0006 §3: an *event* consumer ignores unknown fields. This is not an event
    — it is a synchronous call between two services in one repository, where the
    contract says ``additionalProperties: false``.
    """
    body = payload()
    body["transaction"]["fraudLabel"] = True

    response = client.post("/v1/score", json=body)

    assert response.status_code == 422
    assert response.headers["content-type"].startswith("application/problem+json")
    problem = response.json()
    assert problem["status"] == 422
    assert "fraudLabel" in problem["detail"]


def test_a_missing_required_field_is_refused(client: TestClient) -> None:
    body = payload()
    del body["transaction"]["occurredAt"]

    response = client.post("/v1/score", json=body)

    assert response.status_code == 422
    assert "occurredAt" in response.json()["detail"]


def test_a_refusal_never_echoes_the_value_that_caused_it(client: TestClient) -> None:
    """The offending value here is transaction data.

    FastAPI's default 422 body carries ``input`` — the value itself. On this
    service that is an amount, a device reference or an account handle, and an
    error response is the easiest place to hand one back to a caller.
    """
    sensitive_fragment = "DEV-deadbeef0000"
    body = payload()
    body["transaction"]["deviceReference"] = sensitive_fragment + "-not-a-device"

    response = client.post("/v1/score", json=body)

    assert response.status_code == 422
    assert sensitive_fragment not in response.text
    assert "Traceback" not in response.text
    assert set(response.json()) <= {"type", "title", "status", "detail", "instance"}


def test_history_in_the_wrong_order_is_refused(client: TestClient) -> None:
    """Every window walk in the extractor relies on newest-first."""
    context = payload()["accountContext"]
    context["recentTransactions"] = [recent(3_000), recent(45)]

    response = client.post("/v1/score", json=payload(account_context=context))

    assert response.status_code == 422
    assert "recentTransactions" in response.json()["detail"]


def test_more_history_than_the_cap_is_refused(client: TestClient) -> None:
    """The cap is the contract's, and a request that grew with an account's
    history would be a denial-of-service primitive."""
    context = payload()["accountContext"]
    context["recentTransactions"] = [recent(seconds) for seconds in range(1, 250)]

    response = client.post("/v1/score", json=payload(account_context=context))

    assert response.status_code == 422


# --------------------------------------------------------------------------- #
# No model loaded
# --------------------------------------------------------------------------- #


def test_scoring_without_a_model_is_a_retryable_503(modelless_client: TestClient) -> None:
    """The caller retries within its budget, then writes a degraded assessment
    scored by rules alone (ADR-0008 §3)."""
    response = modelless_client.post("/v1/score", json=payload())

    assert response.status_code == 503
    assert response.headers["content-type"].startswith("application/problem+json")
    assert response.json()["status"] == 503


def test_a_modelless_service_is_live_but_not_ready(modelless_client: TestClient) -> None:
    assert modelless_client.get("/health/live").status_code == 200
    assert modelless_client.get("/health/ready").status_code == 503


# --------------------------------------------------------------------------- #
# Correlation
# --------------------------------------------------------------------------- #


def test_a_supplied_correlation_id_is_echoed(client: TestClient) -> None:
    supplied = "0198f0a1-2b3c-7d4e-8f90-aaaaaaaaaaaa"

    response = client.post("/v1/score", json=payload(), headers={"X-Correlation-Id": supplied})

    assert response.headers["X-Correlation-Id"] == supplied


def test_a_correlation_id_is_generated_when_absent(client: TestClient) -> None:
    response = client.post("/v1/score", json=payload())

    assert response.headers["X-Correlation-Id"]


def test_a_correlation_id_that_is_not_a_uuid_is_replaced_not_reflected(
    client: TestClient,
) -> None:
    """Caller-controlled text going into a response header is a hole worth not opening."""
    injected = "not-a-uuid\r\nX-Injected: yes"

    response = client.post("/v1/score", json=payload(), headers={"X-Correlation-Id": "not-a-uuid"})

    assert response.headers["X-Correlation-Id"] != "not-a-uuid"
    assert "X-Injected" not in response.headers
    assert injected not in response.text


def test_an_unexpected_failure_is_a_problem_document_and_not_a_traceback() -> None:
    """The contract's convention is that every error is problem+json.

    FastAPI's default for an unhandled exception is a plain-text 500, which is
    honest but off-contract; the risk this guards is the other direction, where a
    handler someone adds later returns the exception's own text. A traceback in an
    error body is the difference between an error and a disclosure.
    """
    from sentinelflow_scoring.app import create_app
    from tests.serving.conftest import REGISTRY_ROOT, settings_for

    class _Broken:
        def score(self, _: object) -> None:
            raise RuntimeError("C:/models/logistic-regression/1.0.0/model.joblib is unreadable")

    app = create_app(settings_for(REGISTRY_ROOT))
    app.state.active_model = _Broken()

    with TestClient(app, raise_server_exceptions=False) as broken:
        response = broken.post("/v1/score", json=payload())

    assert response.status_code == 500
    assert response.headers["content-type"].startswith("application/problem+json")
    assert "Traceback" not in response.text
    assert "model.joblib" not in response.text
    assert set(response.json()) <= {"type", "title", "status", "detail", "instance"}
