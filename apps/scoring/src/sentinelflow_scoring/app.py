"""FastAPI application factory, the scoring endpoints, and the operational surface.

Three things this module owns beyond routing, each because the contract asks for
it in a way FastAPI's defaults do not satisfy:

**Errors are RFC 9457, and never echo the input.** FastAPI's default 422 body is a
list of errors carrying ``input`` — the offending value — and ``msg``. On this
service the offending value is a transaction amount, a device reference or an
account handle, and handing it back in an error response is the easiest way to
leak what a caller should not have. The handler here reports *which fields*
failed and nothing about what was in them.

**Every response carries a correlation id.** Accepted from the caller, echoed
back, and bound into the log context, so one scoring call, the transaction it
belongs to and the log lines about it can be tied together. A supplied value that
is not a UUID is replaced rather than echoed: reflecting caller-controlled text
into a response header is a small hole and there is no reason to open it.

**The model is loaded before the first request, or the process does not start.**
See :mod:`sentinelflow_scoring.serving.model` for why a corrupted entry is a
startup failure and an empty registry is not.
"""

from __future__ import annotations

import uuid
from collections.abc import Awaitable, Callable

import structlog
from fastapi import FastAPI, Header, Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from prometheus_client import CONTENT_TYPE_LATEST, REGISTRY, generate_latest
from pydantic import BaseModel

from sentinelflow_scoring import __version__
from sentinelflow_scoring.config import Settings, load_settings
from sentinelflow_scoring.features.schema import ScoreRequest
from sentinelflow_scoring.serving import metrics as collectors
from sentinelflow_scoring.serving.model import ActiveModel, load_active
from sentinelflow_scoring.serving.schema import (
    Liveness,
    ModelInfo,
    Problem,
    Readiness,
    ScoreResponse,
)

logger = structlog.get_logger(__name__)

PROBLEM_MEDIA_TYPE = "application/problem+json"

#: Problem ``type`` URIs. Stable identifiers a caller can branch on, and
#: deliberately under this project's own documentation namespace rather than a
#: dereferenceable URL that would have to exist.
PROBLEM_BASE = "https://github.com/la3679/sentinelflow/docs/problems"
TYPE_VALIDATION = f"{PROBLEM_BASE}/scoring-request-invalid"
TYPE_NO_MODEL = f"{PROBLEM_BASE}/no-model-loaded"
TYPE_INTERNAL = f"{PROBLEM_BASE}/internal-error"

CORRELATION_HEADER = "X-Correlation-Id"

#: Caps on what a validation problem may say about the request. Field *paths* are
#: reported because naming them is the whole use of the response; a caller that
#: sends a thousand unknown fields, or one field named with ten kilobytes, does
#: not get to choose the size of the answer.
MAX_REPORTED_FIELDS = 10
MAX_REPORTED_FIELD_LENGTH = 100


class InfoResponse(BaseModel):
    """Build identity, so a running instance can be traced to what produced it."""

    name: str
    version: str
    git_sha: str


def create_app(settings: Settings | None = None) -> FastAPI:
    """Build the application.

    Taking settings as an argument rather than reading a module-level singleton
    keeps the app constructible under test without mutating the environment.

    :raises sentinelflow_scoring.training.registry.RegistryError: if a registry
        entry is present and does not pass its checks. Deliberately fatal.
    """
    resolved = settings if settings is not None else load_settings()

    app = FastAPI(
        title="SentinelFlow scoring service",
        version=__version__,
        summary="Risk scoring over synthetic transactions.",
        description=(
            "SentinelFlow is an independent educational project operating on synthetic "
            "data. This service makes no real financial decisions."
        ),
        docs_url="/docs",
        openapi_url="/openapi.json",
    )
    app.state.settings = resolved

    active = load_active(resolved)
    app.state.active_model = active
    collectors.MODEL_LOADED.set(1 if active is not None else 0)

    _register_middleware(app)
    _register_error_handlers(app)
    _register_operations(app, resolved)
    _register_scoring(app)

    logger.info(
        "scoring service configured",
        version=__version__,
        port=resolved.port,
        model_loaded=active is not None,
    )
    return app


# --------------------------------------------------------------------------- #
# Middleware
# --------------------------------------------------------------------------- #


def _register_middleware(app: FastAPI) -> None:
    @app.middleware("http")
    async def correlate(
        request: Request, call_next: Callable[[Request], Awaitable[Response]]
    ) -> Response:
        correlation_id = _correlation_id(request.headers.get(CORRELATION_HEADER))
        structlog.contextvars.bind_contextvars(correlation_id=correlation_id)
        try:
            response = await call_next(request)
        finally:
            structlog.contextvars.clear_contextvars()
        response.headers[CORRELATION_HEADER] = correlation_id
        return response


def _correlation_id(supplied: str | None) -> str:
    """The caller's id if it is one, otherwise a fresh one.

    A value that does not parse as a UUID is not echoed. It is caller-controlled
    text going into a response header and into log lines, and the contract types
    the header as a UUID — so replacing it costs a caller nothing it was entitled
    to and closes a header- and log-injection hole without a special case.
    """
    if supplied is None:
        return str(uuid.uuid4())
    try:
        return str(uuid.UUID(supplied))
    except ValueError:
        logger.info("correlation id was not a uuid and was replaced")
        return str(uuid.uuid4())


# --------------------------------------------------------------------------- #
# Errors
# --------------------------------------------------------------------------- #


def _register_error_handlers(app: FastAPI) -> None:
    @app.exception_handler(RequestValidationError)
    async def invalid_request(request: Request, error: RequestValidationError) -> JSONResponse:
        if request.url.path == "/v1/score":
            collectors.SCORE_REQUESTS.labels(outcome=collectors.OUTCOME_INVALID).inc()

        fields = _failed_fields(error)
        logger.info("request rejected", path=request.url.path, fields=fields)
        return _problem(
            Problem(
                type=TYPE_VALIDATION,
                title="The request does not satisfy the scoring contract.",
                status=422,
                detail=(
                    "These fields are missing, malformed, or not part of the contract: "
                    f"{', '.join(fields)}."
                    if fields
                    else "The request body could not be parsed."
                ),
            )
        )

    @app.exception_handler(Exception)
    async def unhandled(request: Request, error: Exception) -> JSONResponse:
        # The exception is logged with its type and nothing from the request. The
        # response carries neither: a traceback in a problem document is the
        # difference between an error and a disclosure.
        logger.exception("unhandled error", path=request.url.path, error_type=type(error).__name__)
        return _problem(
            Problem(
                type=TYPE_INTERNAL,
                title="The scoring service failed to handle the request.",
                status=500,
            )
        )


def _failed_fields(error: RequestValidationError) -> list[str]:
    """The field paths that failed, and nothing else about them.

    Built from ``loc`` alone. ``msg``, ``input`` and ``ctx`` are all excluded, and
    ``input`` is the one that matters: it is the value the caller sent, which on
    this service is transaction data.
    """
    paths = {
        ".".join(str(part) for part in item["loc"])[:MAX_REPORTED_FIELD_LENGTH]
        for item in error.errors()
    }
    return sorted(paths)[:MAX_REPORTED_FIELDS]


def _problem(problem: Problem) -> JSONResponse:
    return JSONResponse(
        status_code=problem.status,
        content=problem.as_body(),
        media_type=PROBLEM_MEDIA_TYPE,
    )


# --------------------------------------------------------------------------- #
# Operational endpoints
# --------------------------------------------------------------------------- #


def _register_operations(app: FastAPI, settings: Settings) -> None:
    @app.get("/health/live", response_model=Liveness, tags=["operations"])
    def live() -> Liveness:
        return Liveness()

    @app.get(
        "/health/ready",
        response_model=Readiness,
        tags=["operations"],
        responses={503: {"description": "Not ready. No model is loaded."}},
    )
    def ready() -> Response:
        model: ActiveModel | None = app.state.active_model
        if model is None:
            # A service with no model is not ready to score, and saying so is what
            # lets the API degrade deliberately instead of discovering it one
            # request at a time.
            return _problem(
                Problem(
                    type=TYPE_NO_MODEL,
                    title="No scoring model is loaded.",
                    status=503,
                    detail="The registry holds no entry for the feature version this build runs.",
                )
            )
        ready_body = Readiness(status="UP", model_loaded=True)
        return JSONResponse(content=ready_body.model_dump(by_alias=True))

    @app.get("/info", response_model=InfoResponse, tags=["operations"])
    def info() -> InfoResponse:
        return InfoResponse(
            name="sentinelflow-scoring",
            version=__version__,
            git_sha=settings.git_sha,
        )

    @app.get("/metrics", include_in_schema=False)
    def prometheus() -> Response:
        # The default registry, not a fresh one: a fresh CollectorRegistry has
        # no collectors attached and would serve an empty scrape that looks
        # healthy.
        return Response(
            content=generate_latest(REGISTRY),
            media_type=CONTENT_TYPE_LATEST,
        )


# --------------------------------------------------------------------------- #
# Scoring endpoints
# --------------------------------------------------------------------------- #


def _register_scoring(app: FastAPI) -> None:
    @app.get(
        "/v1/model",
        response_model=ModelInfo,
        tags=["model"],
        summary="Which model is loaded, and what it was measured at.",
        responses={503: {"description": "No model is loaded."}},
    )
    def active_model() -> Response:
        model: ActiveModel | None = app.state.active_model
        if model is None:
            return _no_model()
        return JSONResponse(content=model.info.model_dump(by_alias=True, exclude_none=True))

    @app.post(
        "/v1/score",
        response_model=ScoreResponse,
        tags=["scoring"],
        summary="Score one transaction.",
        responses={
            422: {"description": "The request does not satisfy this contract. Never retried."},
            503: {"description": "No model is loaded. Retryable."},
        },
    )
    def score(
        request: ScoreRequest,
        # Declared and not read. The middleware is what accepts and echoes it, and
        # declaring it here is what puts it in the generated OpenAPI document
        # beside the contract's own `X-Correlation-Id` parameter. Without it the
        # two documents disagree about a header the service does honour.
        x_correlation_id: str | None = Header(default=None),
    ) -> Response:
        model: ActiveModel | None = app.state.active_model
        if model is None:
            collectors.SCORE_REQUESTS.labels(outcome=collectors.OUTCOME_UNAVAILABLE).inc()
            return _no_model()

        outcome = model.score(request)
        collectors.SCORE_REQUESTS.labels(outcome=collectors.OUTCOME_SCORED).inc()
        collectors.INFERENCE_SECONDS.observe(outcome.inference_duration_ms / 1000.0)

        logger.info(
            "transaction scored",
            transaction_id=request.transaction.transaction_id,
            model_version=model.manifest.model_version,
            feature_version=model.manifest.feature_version,
            model_score=round(outcome.model_score, 4),
            inference_duration_ms=round(outcome.inference_duration_ms, 3),
            warnings=len(outcome.warnings),
        )
        body = model.response(outcome)
        return JSONResponse(content=body.model_dump(by_alias=True))


def _no_model() -> JSONResponse:
    """The 503 both scoring endpoints return when nothing is loaded.

    Retryable by design: the caller retries within its budget and then writes a
    degraded assessment scored by rules alone (ADR-0008 §3).
    """
    return _problem(
        Problem(
            type=TYPE_NO_MODEL,
            title="No scoring model is loaded.",
            status=503,
            detail="Retry within your budget, then fall back to the rules baseline.",
        )
    )


app = create_app()
