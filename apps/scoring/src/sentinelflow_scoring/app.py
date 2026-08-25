"""FastAPI application factory and operational endpoints.

The scoring endpoints themselves arrive in Phase 4. What this module owns from
the start is the operational surface every phase after it depends on: liveness,
readiness, build identity, and metrics.
"""

from __future__ import annotations

from typing import Literal

import structlog
from fastapi import FastAPI, Response
from prometheus_client import CONTENT_TYPE_LATEST, REGISTRY, generate_latest
from pydantic import BaseModel, Field

from sentinelflow_scoring import __version__
from sentinelflow_scoring.config import Settings, load_settings

logger = structlog.get_logger(__name__)


class HealthResponse(BaseModel):
    """Liveness answer: the process is running and can serve a request."""

    status: Literal["UP"] = "UP"


class ReadinessResponse(BaseModel):
    """Readiness answer: the process can do useful work.

    Liveness and readiness are deliberately separate. A process that is up but
    cannot reach its dependencies is live and not ready; collapsing the two
    makes an orchestrator restart a container that only needed to be taken out
    of rotation.

    ``model_loaded`` is false until Phase 4 loads a model. It is reported rather
    than assumed, so readiness never claims a capability the service lacks.
    """

    status: Literal["UP", "DOWN"] = "UP"
    model_loaded: bool = Field(
        default=False,
        description="Whether a scoring model is loaded. No model exists before Phase 4.",
    )


class InfoResponse(BaseModel):
    """Build identity, so a running instance can be traced to what produced it."""

    name: str
    version: str
    git_sha: str


def create_app(settings: Settings | None = None) -> FastAPI:
    """Build the application.

    Taking settings as an argument rather than reading a module-level singleton
    keeps the app constructible under test without mutating the environment.
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

    @app.get("/health/live", response_model=HealthResponse, tags=["operations"])
    def live() -> HealthResponse:
        return HealthResponse()

    @app.get("/health/ready", response_model=ReadinessResponse, tags=["operations"])
    def ready() -> ReadinessResponse:
        return ReadinessResponse()

    @app.get("/info", response_model=InfoResponse, tags=["operations"])
    def info() -> InfoResponse:
        return InfoResponse(
            name="sentinelflow-scoring",
            version=__version__,
            git_sha=resolved.git_sha,
        )

    @app.get("/metrics", include_in_schema=False)
    def metrics() -> Response:
        # The default registry, not a fresh one: a fresh CollectorRegistry has
        # no collectors attached and would serve an empty scrape that looks
        # healthy. Application metrics registered from Phase 4 land here.
        return Response(
            content=generate_latest(REGISTRY),
            media_type=CONTENT_TYPE_LATEST,
        )

    logger.info("scoring service configured", version=__version__, port=resolved.port)
    return app


app = create_app()
