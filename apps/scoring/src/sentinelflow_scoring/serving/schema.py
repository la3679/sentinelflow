"""Response models for ``POST /v1/score`` and ``GET /v1/model``.

Field-for-field with ``contracts/openapi/sentinelflow-scoring.yaml``, which is
authoritative on this side exactly as it is on the request side, and for the same
reason: nothing in a Python build notices when a field is added to a YAML file and
not to a class. ``tests/serving/test_response_schema_matches_contract.py`` asserts
they have not drifted.

**Bounds are enforced here, not hoped for.** ``reasons`` is capped at ten and
``warnings`` at ten because the contract caps them, and an uncapped response is an
uncapped response whether or not anything today would fill it. A caller that
allocates for what a contract promises is entitled to be right.
"""

from __future__ import annotations

from typing import Annotated, Any, Literal

from pydantic import BaseModel, ConfigDict, Field

from sentinelflow_scoring.features.schema import to_camel

#: The contract's caps. Both are the same number today and are named separately
#: because they bound different things and will not necessarily move together.
MAX_REASONS = 10
MAX_WARNINGS = 10
MAX_WARNING_LENGTH = 200

#: The contract's range for ``modelScore``. Higher is riskier, and it is not a
#: probability — see ``candidates.to_contract_score`` and ADR-0010 §4.
SCORE_MIN = 0.0
SCORE_MAX = 100.0

REASON_CODE_PATTERN = r"^[A-Z][A-Z0-9_]{2,63}$"
SHA256_PATTERN = r"^[0-9a-f]{64}$"


class _Response(BaseModel):
    """Shared configuration. Camel-case on the wire, snake_case in Python.

    ``extra="forbid"`` on a *response* model is not about rejecting input — it is
    about a field being added to one of these classes and reaching the wire
    without anyone adding it to the contract first.
    """

    model_config = ConfigDict(
        extra="forbid",
        frozen=True,
        populate_by_name=True,
        alias_generator=to_camel,
    )


class ReasonContribution(_Response):
    """One reason the score is what it is.

    A code and a signed contribution, not free text: the console groups and
    filters on the code, and an analyst's written justification has to survive the
    model being replaced.
    """

    code: Annotated[str, Field(pattern=REASON_CODE_PATTERN)]
    contribution: float


class ScoreResponse(_Response):
    """What ``POST /v1/score`` returns."""

    model_version: str
    feature_version: str
    model_score: Annotated[float, Field(ge=SCORE_MIN, le=SCORE_MAX)]
    reasons: Annotated[list[ReasonContribution], Field(max_length=MAX_REASONS)]
    inference_duration_ms: Annotated[float, Field(ge=0)]
    warnings: Annotated[
        list[Annotated[str, Field(max_length=MAX_WARNING_LENGTH)]],
        Field(max_length=MAX_WARNINGS),
    ]


class ModelMetrics(_Response):
    """Measured on this model's own evaluation split, on synthetic data.

    **Accuracy is deliberately absent**, here and in the contract. Suspicious
    transactions are extremely imbalanced, so a model that predicts "not
    suspicious" for everything scores well on accuracy and is worthless.
    Reporting it at all invites quoting it.
    """

    precision: Annotated[float, Field(ge=0, le=1)]
    recall: Annotated[float, Field(ge=0, le=1)]
    f1: Annotated[float, Field(ge=0, le=1)]
    average_precision: Annotated[float, Field(ge=0, le=1)]
    roc_auc: Annotated[float | None, Field(ge=0, le=1)] = None
    false_positive_rate: Annotated[float, Field(ge=0, le=1)]
    operating_threshold: Annotated[float, Field(ge=SCORE_MIN, le=SCORE_MAX)]
    alert_volume_at_threshold: Annotated[int | None, Field(ge=0)] = None


class ModelInfo(_Response):
    """Which model is loaded, and what it was measured at.

    The operating point here is a **recommendation**, not the threshold that ran:
    the API applies its own alerting policy to a final score that also folds in a
    rule score this model never saw (ADR-0008 §4).
    """

    model_version: str
    feature_version: str
    algorithm: str
    trained_at: str
    artifact_sha256: Annotated[str, Field(pattern=SHA256_PATTERN)]
    dataset_fingerprint: str | None = None
    metrics: ModelMetrics


class Liveness(_Response):
    """Liveness answer: the process is running and can serve a request."""

    status: Literal["UP"] = "UP"


class Readiness(_Response):
    """Readiness answer: the process can do useful work.

    Liveness and readiness are deliberately separate. A process that is up but
    cannot reach its dependencies is live and not ready; collapsing the two makes
    an orchestrator restart a container that only needed to be taken out of
    rotation.

    ``modelLoaded`` is reported rather than assumed, so readiness never claims a
    capability the service lacks.
    """

    status: Literal["UP", "DOWN"] = "UP"
    model_loaded: bool = Field(
        default=False,
        description="Whether a scoring model is loaded and usable.",
    )


class Problem(BaseModel):
    """RFC 9457.

    **Never contains a traceback, a file path, or an echo of the input.** An error
    response is the easiest place to hand a caller back something it should not
    see, and the fields that would do it — a stack trace, the offending value, the
    directory a model failed to load from — are the ones this class does not have.

    Not a ``_Response``: ``type`` is spelled ``type`` on the wire and in the
    contract, and running it through the camel-case generator would be machinery
    with nothing to do.
    """

    model_config = ConfigDict(extra="forbid", frozen=True)

    type: str
    title: str
    status: int
    detail: str | None = None
    instance: str | None = None

    def as_body(self) -> dict[str, Any]:
        """The wire form, without the optional fields that were not set."""
        return self.model_dump(exclude_none=True)
