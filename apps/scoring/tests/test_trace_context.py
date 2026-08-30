"""Reading the caller's trace, and refusing to invent one.

Two halves, and the second is the one worth having. Parsing a well-formed header
is arithmetic; deciding what to do with a malformed one is a judgement, and the
judgement here is that a trace id nothing can find is worse than no trace id —
an operator who searches for it gets an empty result and no indication that the
value was never real.
"""

from __future__ import annotations

import io
import json
from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient

from sentinelflow_scoring.app import create_app
from sentinelflow_scoring.config import Settings
from sentinelflow_scoring.log import configure_logging
from sentinelflow_scoring.trace import parse_traceparent
from tests.serving.conftest import REGISTRY_ROOT, payload

TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736"
SPAN_ID = "00f067aa0ba902b7"
TRACEPARENT = f"00-{TRACE_ID}-{SPAN_ID}-01"


@pytest.fixture
def logs() -> io.StringIO:
    return io.StringIO()


@pytest.fixture
def client(logs: io.StringIO) -> Iterator[TestClient]:
    application = create_app(
        Settings(git_sha="0" * 40, models_root=REGISTRY_ROOT, log_format="json", log_level="INFO")
    )
    configure_logging("INFO", "json", stream=logs)
    with TestClient(application) as running:
        yield running


def lines(logs: io.StringIO) -> list[dict[str, object]]:
    return [json.loads(line) for line in logs.getvalue().splitlines() if line.strip()]


# --------------------------------------------------------------------------- #
# The parser
# --------------------------------------------------------------------------- #


def test_a_well_formed_header_yields_both_identifiers() -> None:
    parsed = parse_traceparent(TRACEPARENT)

    assert parsed is not None
    assert parsed.trace_id == TRACE_ID
    assert parsed.span_id == SPAN_ID


@pytest.mark.parametrize(
    "supplied",
    [
        None,
        "",
        "not-a-traceparent",
        # A version this build cannot read. Treated as absent rather than
        # guessed at: a later version may append fields, and the fields we want
        # may not be where they are today.
        f"01-{TRACE_ID}-{SPAN_ID}-01",
        # Structurally valid, semantically not: the specification says a
        # receiver must treat an all-zero id as no trace.
        f"00-{'0' * 32}-{SPAN_ID}-01",
        f"00-{TRACE_ID}-{'0' * 16}-01",
        # Right shape, wrong lengths.
        f"00-{TRACE_ID[:31]}-{SPAN_ID}-01",
        # Upper case. The specification fixes lower-case hex, and accepting
        # both would mean the same trace could be written two ways.
        f"00-{TRACE_ID.upper()}-{SPAN_ID}-01",
    ],
)
def test_anything_this_build_cannot_read_is_no_trace_at_all(supplied: str | None) -> None:
    assert parse_traceparent(supplied) is None


# --------------------------------------------------------------------------- #
# The middleware
# --------------------------------------------------------------------------- #


def test_the_callers_trace_reaches_this_services_log_lines(
    client: TestClient, logs: io.StringIO
) -> None:
    """The point of the whole exercise: one id joins two services' logs."""
    response = client.post("/v1/score", json=payload(), headers={"traceparent": TRACEPARENT})
    assert response.status_code == 200, response.text

    scored = [line for line in lines(logs) if line.get("event") == "transaction scored"]
    assert scored, "the service logged nothing about the request"
    for line in scored:
        assert line["trace_id"] == TRACE_ID
        assert line["parent_span_id"] == SPAN_ID
        # Still carries its own, because they answer different questions and
        # the correlation id is the one that appears in a problem document.
        assert "correlation_id" in line


def test_a_request_without_a_trace_logs_no_trace_id(client: TestClient, logs: io.StringIO) -> None:
    """An absent field, not an empty string or a placeholder.

    A ``trace_id: ""`` on every line from an untraced caller is a field that
    looks queryable and matches nothing.
    """
    response = client.post("/v1/score", json=payload())
    assert response.status_code == 200, response.text

    scored = [line for line in lines(logs) if line.get("event") == "transaction scored"]
    assert scored
    for line in scored:
        assert "trace_id" not in line


def test_a_malformed_trace_header_is_dropped_rather_than_logged(
    client: TestClient, logs: io.StringIO
) -> None:
    """Caller-controlled text that reaches the log context is an injection surface.

    The same reasoning that makes the correlation id refuse to echo a non-UUID.
    """
    injected = "00-not-a-trace-id\ninjected line"

    response = client.post("/v1/score", json=payload(), headers={"traceparent": injected})
    assert response.status_code == 200, response.text

    assert "injected line" not in logs.getvalue()
    for line in lines(logs):
        assert "trace_id" not in line
