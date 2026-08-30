"""Log configuration: JSON where something reads it, text where a person does.

``structlog`` has been a dependency and every log call in this service has gone
through it since Phase 4, but nothing ever called :func:`structlog.configure` —
so it rendered with its own console defaults, which are neither the developer
format anybody chose nor a format a collector can parse. This module is the
missing call.

**Two renderers, one decision** (ADR-0016 §4). ``json`` in a container, because a
log line is only structured if whatever reads it can find the fields. ``console``
on a terminal, because a person reading a stack trace at three in the morning
should not be reading escaped JSON. The default is ``console`` and the compose
service sets ``json``, which is the same shape as the API's own arrangement.

**Standard-library logging is routed through the same pipeline.** uvicorn, FastAPI
and anything else that logs would otherwise write their own format alongside
this one, and a stream that is JSON on some lines and not on others is a stream
nothing can parse. ``ProcessorFormatter`` puts every record through the same
processor chain, so an access log line ends up with the same keys as a line this
service wrote itself.

**Nothing here redacts.** Redaction is a property of what a call site passes, not
of a filter over rendered output — a deny-list would fail open on the first field
somebody adds, and would pass its tests by matching only the strings whoever wrote
them had thought of. What enforces the rule is that no call in this service passes
a request, a payload or a model input to a logger, and ``tests/test_log_redaction.py``
asserts the negative against a captured stream.

Named ``log`` rather than ``logging``: a module named for a standard-library one
is legal and reads like a mistake every time somebody opens the import block.
"""

from __future__ import annotations

import logging
import sys
from typing import IO, Any

import structlog

#: The two supported renderings. Anything else is a configuration error rather
#: than a silent fallback: a service that quietly logs in a format nobody asked
#: for is one whose logs nobody notices are wrong.
FORMATS = ("console", "json")


def configure_logging(log_level: str, log_format: str, stream: IO[str] | None = None) -> None:
    """Point structlog and the standard library at one renderer.

    Idempotent, because the application factory is called once per process in
    production and once per test in the suite, and a chain configured twice
    duplicates every processor in it.

    :param log_level: the root level, as a name such as ``INFO``.
    :param log_format: ``console`` or ``json``.
    :param stream: where lines go. ``None`` means ``sys.stdout``, which is what
        the service runs on and what a container reads.

        The parameter exists because ``logging.StreamHandler`` binds its stream
        once, at construction. That is right in a process whose stdout never
        moves and wrong under a test harness whose stdout does: the handler ends
        up holding a buffer nothing reads any more, and a test that searches the
        log for a value it must not find then passes against an empty string.
        A seam is cheaper than a handler that re-resolves ``sys.stdout`` on every
        line for the sake of one caller.
    """
    if log_format not in FORMATS:
        raise ValueError(
            f"log_format must be one of {', '.join(FORMATS)}; {log_format!r} would leave the "
            "service logging in a format nothing was configured to read"
        )

    level = logging.getLevelNamesMapping().get(log_level.upper())
    if level is None:
        raise ValueError(
            f"log_level {log_level!r} is not a level name. A level that does not resolve would "
            "silently leave the root logger at WARNING and hide every INFO line this service writes"
        )

    # Shared by both paths: everything up to the point where a rendering is
    # chosen. contextvars is what carries the correlation id bound by the
    # middleware onto every line written while handling that request.
    shared: list[Any] = [
        structlog.contextvars.merge_contextvars,
        structlog.stdlib.add_log_level,
        structlog.stdlib.add_logger_name,
        structlog.processors.TimeStamper(fmt="iso", utc=True),
        structlog.processors.StackInfoRenderer(),
        structlog.processors.UnicodeDecoder(),
    ]

    renderer: Any = (
        structlog.processors.JSONRenderer()
        if log_format == "json"
        # colors off: this stream is a container's stdout as often as it is a
        # terminal, and escape codes in a captured log are noise a reader has to
        # look past.
        else structlog.dev.ConsoleRenderer(colors=False)
    )

    structlog.configure(
        processors=[
            *shared,
            # format_exc_info only on the structlog path: ProcessorFormatter
            # renders the exception itself on the stdlib path, and running both
            # prints the traceback twice.
            structlog.processors.format_exc_info,
            structlog.stdlib.ProcessorFormatter.wrap_for_formatter,
        ],
        logger_factory=structlog.stdlib.LoggerFactory(),
        wrapper_class=structlog.stdlib.BoundLogger,
        cache_logger_on_first_use=True,
    )

    formatter = structlog.stdlib.ProcessorFormatter(
        foreign_pre_chain=shared,
        processors=[
            # Removes the bookkeeping key wrap_for_formatter leaves behind. Without
            # it every JSON line carries an internal field nothing consumes.
            structlog.stdlib.ProcessorFormatter.remove_processors_meta,
            renderer,
        ],
    )

    handler = logging.StreamHandler(sys.stdout if stream is None else stream)
    handler.setFormatter(formatter)

    root = logging.getLogger()
    # Replaced rather than appended. uvicorn installs its own handlers, and
    # leaving them in place means every line is emitted twice in two formats.
    root.handlers = [handler]
    root.setLevel(level)

    for name in ("uvicorn", "uvicorn.error", "uvicorn.access"):
        library = logging.getLogger(name)
        library.handlers = []
        # Propagate to the root handler above rather than keeping their own, so
        # there is exactly one place that decides what a line looks like.
        library.propagate = True
