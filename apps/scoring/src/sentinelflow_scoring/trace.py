"""W3C trace context, read from the caller and put on this service's log lines.

**What this does and, more usefully, what it does not.** It parses the
``traceparent`` header the API sends and binds the trace and span ids into the
log context, so every line this service writes about a request carries the same
``trace_id`` as the ingestion that caused it. It does **not** create spans and it
does not export anything.

That is a deliberate boundary, and it is worth stating because the alternative
looks strictly better until it is priced. Emitting spans from here means the
OpenTelemetry SDK, an OTLP exporter and FastAPI auto-instrumentation — three
runtime dependencies on a service whose entire dependency list is argued for in
ADR-0004 — to add one server span inside a hop the caller already measures. The
API wraps every scoring call in a client span, so the trace already shows the
hop, its duration and its outcome; what the API cannot see is what happened
inside, and that is answered by this service's own inference histogram and by
these log lines, which are now joinable to the trace by id.

If a future phase wants a flame graph through the model, the dependencies are the
price and this module is where the decision changes.

**A malformed header is dropped, never repaired.** The value is caller-supplied
text that reaches the log context; the same reasoning that makes
``_correlation_id`` refuse to echo a non-UUID applies here, with the addition
that a trace id invented to replace a broken one points an operator at a trace
that does not exist.
"""

from __future__ import annotations

import re
from typing import NamedTuple

#: The header, spelled as the specification spells it. Lower case: HTTP headers
#: are case-insensitive and Starlette normalises them, but the name is written
#: this way everywhere else in this system and a reader should not have to
#: wonder whether the difference means something.
TRACEPARENT_HEADER = "traceparent"

#: version "00" only. A later version may append fields, and a parser that
#: accepted a version it had never seen would be guessing at where the fields it
#: needs are. An unrecognised version is treated as no trace at all, which is
#: honest: this build genuinely cannot read it.
_TRACEPARENT = re.compile(r"^00-(?P<trace_id>[0-9a-f]{32})-(?P<span_id>[0-9a-f]{16})-[0-9a-f]{2}$")

#: An all-zero id is structurally valid and semantically invalid — the
#: specification says a receiver must treat it as no trace. Without this check it
#: would reach the logs as a plausible-looking identifier that matches nothing.
_ZERO_TRACE = "0" * 32
_ZERO_SPAN = "0" * 16


class TraceContext(NamedTuple):
    """The two identifiers worth putting on a log line."""

    trace_id: str
    span_id: str


def parse_traceparent(supplied: str | None) -> TraceContext | None:
    """The caller's trace, or ``None`` when there is not one this build can use.

    :param supplied: the raw header value, or ``None`` when the caller sent none.
    """
    if supplied is None:
        return None

    matched = _TRACEPARENT.match(supplied.strip())
    if matched is None:
        return None

    trace_id = matched.group("trace_id")
    span_id = matched.group("span_id")
    if trace_id == _ZERO_TRACE or span_id == _ZERO_SPAN:
        return None

    return TraceContext(trace_id=trace_id, span_id=span_id)
