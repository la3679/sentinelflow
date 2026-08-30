"""What this service writes to its log, and what it must never write.

ADR-0016 §4 states the rule — a log line is built from named fields chosen at the
call site, never by serialising a request — and this asserts the negative against
a real captured stream rather than against a hand-built string. A test that
formatted a line itself and searched it would prove only that the test author's
example was safe.

**Captured through the shipped pipeline, not through ``caplog``.**
:func:`configure_logging` replaces the root handlers, deliberately, because
uvicorn installs its own and two sets means every line emitted twice in two
formats. That also removes pytest's capture handler, so ``caplog.records`` is
empty here. ``capsys`` does not work either: ``logging.StreamHandler`` binds its
stream once, and pytest swaps its capture buffer between the setup and call
phases, so the handler keeps writing into a buffer ``readouterr`` no longer
reads — which made three of these assertions pass against an empty string before
this was understood. The tests therefore point the real handler at a buffer they
own, and one separate assertion covers the default being ``sys.stdout``.

**Every level, including DEBUG.** An assertion that holds only because the root
logger sits at ``INFO`` is an assertion about configuration, and configuration is
a thing a deployment changes.

The planted values are distinctive so a match is unambiguous. ``4242.4242`` and
``DEV-beefbeefbeef`` cannot appear in a log line by coincidence, which a plausible
amount like ``12.00`` certainly could.
"""

from __future__ import annotations

import io
import json
import logging
import sys
from collections.abc import Iterator
from typing import Any

import pytest
from fastapi.testclient import TestClient

from sentinelflow_scoring.app import create_app
from sentinelflow_scoring.config import Settings
from sentinelflow_scoring.log import configure_logging
from tests.serving.conftest import REGISTRY_ROOT, payload

#: Values planted in the request. Each is the shape of something the service is
#: forbidden to log: a monetary amount and a device handle.
AMOUNT = "4242.4242"
DEVICE = "DEV-beefbeefbeef"
FORBIDDEN = (AMOUNT, DEVICE)


@pytest.fixture
def logs() -> io.StringIO:
    """The buffer the service's own handler writes into for the duration of a test."""
    return io.StringIO()


@pytest.fixture
def client(logs: io.StringIO) -> Iterator[TestClient]:
    """A client logging as JSON at DEBUG, which is the container's format at its loudest.

    ``create_app`` configures logging to stdout, exactly as it does in the
    container; the call below repoints the same handler at a buffer this test can
    read. It is the shipped pipeline either way — same processors, same renderer,
    same root handler — rather than a second one built to be observable.
    """
    application = create_app(
        Settings(
            git_sha="0" * 40,
            models_root=REGISTRY_ROOT,
            log_format="json",
            log_level="DEBUG",
        )
    )
    configure_logging("DEBUG", "json", stream=logs)
    with TestClient(application) as running:
        yield running


def scoring_request() -> dict[str, Any]:
    body = payload()
    body["transaction"]["amount"] = {"value": AMOUNT, "currency": "GBP"}
    body["transaction"]["deviceReference"] = DEVICE
    return body


def written(logs: io.StringIO) -> str:
    """Everything the service has emitted so far."""
    return logs.getvalue()


def test_a_scored_request_leaves_no_amount_or_device_in_the_log(
    client: TestClient, logs: io.StringIO
) -> None:
    response = client.post("/v1/score", json=scoring_request())
    assert response.status_code == 200, response.text

    stream = written(logs)
    for value in FORBIDDEN:
        assert value not in stream, f"{value} reached the log"


def test_a_rejected_request_names_its_fields_and_echoes_none_of_them(
    client: TestClient, logs: io.StringIO
) -> None:
    """The 422 path is the one that most wants to be helpful with the input.

    FastAPI's own validation error carries ``input`` — the offending value — and
    the handler replaces it with the field names alone. This is the assertion that
    keeps it that way, on both the log and the response.
    """
    broken = scoring_request()
    broken["transaction"]["amount"] = {"value": AMOUNT, "currency": "NOT-A-CURRENCY"}

    response = client.post("/v1/score", json=broken)
    assert response.status_code == 422, response.text

    stream = written(logs)
    assert AMOUNT not in stream
    assert AMOUNT not in response.text

    # The negative is only worth something beside the positive. A service that
    # logged nothing would pass every assertion above and be useless at three in
    # the morning, so the line has to still say what was wrong and where.
    assert "request rejected" in stream
    assert "transaction.amount.currency" in stream


def test_the_json_renderer_produces_one_parsable_object_per_line(
    client: TestClient, logs: io.StringIO
) -> None:
    """A stream that is JSON on some lines and not on others is a stream nothing parses.

    The library loggers matter more than this service's own here: uvicorn, asyncio
    and httpx all log, and before :func:`configure_logging` they each wrote in
    their own format beside the structured lines.
    """
    client.post("/v1/score", json=scoring_request())

    lines = [line for line in written(logs).splitlines() if line.strip()]
    assert lines, "the service wrote nothing to stdout"

    loggers = set()
    for line in lines:
        parsed = json.loads(line)
        assert "event" in parsed, line
        assert "level" in parsed, line
        assert "timestamp" in parsed, line
        loggers.add(parsed.get("logger"))

    assert any(
        name is not None and not name.startswith("sentinelflow_scoring") for name in loggers
    ), f"no library logger was routed through the same renderer: {loggers}"


def test_a_scored_request_carries_its_correlation_id_onto_every_line(
    client: TestClient, logs: io.StringIO
) -> None:
    """The id the caller sent is what ties this service's lines to the API's.

    Bound by the middleware into ``contextvars``, which is why it reaches lines
    written by code that never sees the request.
    """
    correlation = "0198f0a1-2b3c-7d4e-8f90-aaaaaaaaaaaa"

    response = client.post(
        "/v1/score",
        json=scoring_request(),
        headers={"X-Correlation-Id": correlation},
    )
    assert response.status_code == 200, response.text

    during_request = [
        json.loads(line)
        for line in written(logs).splitlines()
        if line.strip() and "correlation_id" in line
    ]
    assert during_request, "no line carried a correlation id"
    for entry in during_request:
        assert entry["correlation_id"] == correlation


def test_the_default_stream_is_stdout() -> None:
    """The buffer above is a test seam; this is the assertion that it is only that.

    Without it every test in this file could pass while the shipped service wrote
    its logs somewhere a container never reads.
    """
    configure_logging("INFO", "json")

    handlers = logging.getLogger().handlers
    assert len(handlers) == 1, "uvicorn's handlers must be replaced, not joined"
    assert isinstance(handlers[0], logging.StreamHandler)
    assert handlers[0].stream is sys.stdout
