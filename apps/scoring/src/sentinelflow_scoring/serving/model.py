"""The active model: finding it, validating it, and scoring one request with it.

**Loaded once, at startup, or the process does not start.** A model discovered on
the first request would make the first request pay for it and would let a
corrupted artifact sit undetected until traffic arrived. Everything that can be
checked about an entry is checked before the service accepts a connection:

- the artifact's SHA-256 against its manifest,
- the manifest's feature version against this build's,
- the manifest's column order against this build's,
- and that the metrics document beside it describes the model that was selected.

The first three are :func:`sentinelflow_scoring.training.registry.load`'s. The
fourth is here, because ``/v1/model`` publishes those figures and a manifest that
cannot be matched to its own metrics is an entry that cannot honestly answer it.

**No model at all is a different thing from a broken one.** An empty registry
returns ``None`` and the service runs, reporting ``modelLoaded: false``, refusing
``/v1/score`` with a retryable 503, and letting the API degrade to rules —
which is a state the contract has a response for. A *malformed* entry raises, and
the process exits: a checksum mismatch is a corrupted or substituted artifact, and
serving around it quietly is the one behaviour with no defensible reading.
"""

from __future__ import annotations

import time
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import structlog
from numpy.typing import NDArray

from sentinelflow_scoring.config import Settings
from sentinelflow_scoring.features import FEATURE_NAMES, FEATURE_VERSION, extract
from sentinelflow_scoring.features.schema import ScoreRequest
from sentinelflow_scoring.serving import reasons
from sentinelflow_scoring.serving.schema import (
    MAX_WARNING_LENGTH,
    MAX_WARNINGS,
    ModelInfo,
    ModelMetrics,
    ReasonContribution,
    ScoreResponse,
)
from sentinelflow_scoring.training import registry
from sentinelflow_scoring.training.candidates import positive_probability, to_contract_score
from sentinelflow_scoring.training.registry import ModelManifest, RegistryError

logger = structlog.get_logger(__name__)


@dataclass(frozen=True, slots=True)
class ScoreOutcome:
    """One scoring result, before it is shaped into a response."""

    model_score: float
    reasons: list[ReasonContribution]
    warnings: list[str]
    inference_duration_ms: float


@dataclass(frozen=True, slots=True)
class ActiveModel:
    """A validated registry entry, and everything the two endpoints serve from it.

    ``info`` is built at load time rather than per request. It is derived entirely
    from two files that cannot change while the process runs, and building it here
    means a malformed metrics document is a startup failure instead of a 500 on
    whichever request happened to ask.
    """

    estimator: object
    manifest: ModelManifest
    info: ModelInfo

    def score(self, request: ScoreRequest) -> ScoreOutcome:
        """Score one transaction. No side effects, no state between calls.

        That is the whole of the contract's idempotency claim: the same request
        returns the same score for the same model and feature version, so a retry
        is free and a duplicate is harmless.
        """
        started = time.perf_counter()

        extraction = extract(request)
        # Built in the manifest's order, which load() has already asserted is this
        # build's order. Indexing by name rather than relying on dict insertion
        # order is what makes that assertion load-bearing instead of decorative.
        vector: NDArray[np.float64] = np.array(
            [[extraction.features[name] for name in self.manifest.feature_names]],
            dtype=np.float64,
        )

        probability = positive_probability(self.estimator, vector)
        model_score = float(to_contract_score(probability)[0])

        warnings = list(extraction.warnings)
        attribution = reasons.contributions(self.estimator, vector)
        if attribution is None:
            warnings.append(reasons.NO_ATTRIBUTION_WARNING)
            explained: list[ReasonContribution] = []
        else:
            explained = reasons.build(attribution, extraction.features)

        # Measured around the work this service actually did, so the caller can
        # tell a slow model from a slow network without guessing.
        duration_ms = (time.perf_counter() - started) * 1000.0

        return ScoreOutcome(
            model_score=model_score,
            reasons=explained,
            warnings=_bounded(warnings),
            inference_duration_ms=duration_ms,
        )

    def response(self, outcome: ScoreOutcome) -> ScoreResponse:
        """The wire form of a score, carrying the versions that make it readable later."""
        return ScoreResponse(
            model_version=self.manifest.model_version,
            feature_version=self.manifest.feature_version,
            model_score=outcome.model_score,
            reasons=outcome.reasons,
            inference_duration_ms=outcome.inference_duration_ms,
            warnings=outcome.warnings,
        )


def load_active(settings: Settings) -> ActiveModel | None:
    """Resolve, validate and load the entry this process will serve.

    :raises RegistryError: if an entry is named or discovered and then fails any
        of its checks, or if discovery finds more than one candidate.
    """
    directory = _resolve(settings)
    if directory is None:
        logger.warning(
            "no model in the registry; scoring will refuse with 503 and the API degrades to rules",
            models_root=str(settings.models_root),
            feature_version=FEATURE_VERSION,
        )
        return None

    estimator, manifest = registry.load(
        directory,
        FEATURE_VERSION,
        expected_feature_names=FEATURE_NAMES,
    )
    info = _info_from(directory, manifest)

    logger.info(
        "model loaded",
        model_version=manifest.model_version,
        feature_version=manifest.feature_version,
        algorithm=manifest.model_name,
        artifact_sha256=manifest.artifact_sha256,
    )
    return ActiveModel(estimator=estimator, manifest=manifest, info=info)


def _resolve(settings: Settings) -> Path | None:
    """The pinned entry if one is configured, otherwise the discovered one."""
    if settings.model_name is not None and settings.model_version is not None:
        pinned = registry.entry_directory(
            settings.models_root, settings.model_name, settings.model_version
        )
        if not pinned.is_dir():
            raise RegistryError(
                f"{settings.model_name}/{settings.model_version} was pinned by configuration and "
                "is not in the registry. A pin that silently fell back to discovery would serve a "
                "model nobody asked for."
            )
        return pinned
    return registry.discover(settings.models_root, FEATURE_VERSION)


def _info_from(directory: Path, manifest: ModelManifest) -> ModelInfo:
    """Build ``/v1/model``'s answer from the manifest and the metrics document.

    ``algorithm`` is the manifest's ``model_name`` — ``logistic-regression`` —
    rather than its ``algorithm`` field, which holds the candidate's full
    description. The contract asks for "what it is, in one phrase" and gives
    ``logistic-regression`` as the example; the description is in the model card,
    which is where a sentence belongs.
    """
    metrics = registry.read_metrics(directory)
    selected = metrics.get("selected")
    if selected != manifest.model_name:
        raise RegistryError(
            f"{directory}/metrics.json reports {selected!r} as the selected model while its "
            f"manifest describes {manifest.model_name!r}. The figures beside an artifact have to "
            "be the figures for that artifact, or /v1/model publishes another model's numbers."
        )

    holdout = metrics.get("candidates", {}).get(selected, {}).get("holdout")
    if not isinstance(holdout, dict):
        raise RegistryError(
            f"{directory}/metrics.json has no holdout metrics for {selected!r}. /v1/model would "
            "have nothing to publish, and a model whose measurements cannot be found is not one "
            "to serve."
        )

    return ModelInfo(
        model_version=manifest.model_version,
        feature_version=manifest.feature_version,
        algorithm=manifest.model_name,
        trained_at=manifest.trained_at,
        artifact_sha256=manifest.artifact_sha256,
        dataset_fingerprint=manifest.dataset_sha256,
        metrics=ModelMetrics(
            precision=float(holdout["precision"]),
            recall=float(holdout["recall"]),
            f1=float(holdout["f1"]),
            average_precision=float(holdout["pr_auc"]),
            roc_auc=float(holdout["roc_auc"]),
            false_positive_rate=float(holdout["false_positive_rate"]),
            operating_threshold=float(holdout["threshold"]),
            alert_volume_at_threshold=int(holdout["alert_count"]),
        ),
    )


def _bounded(warnings: list[str]) -> list[str]:
    """The contract's caps, applied rather than assumed.

    Nothing today produces more than a handful of short warnings. Applying the cap
    anyway is the difference between a response that satisfies the contract and
    one that happens to.
    """
    return [warning[:MAX_WARNING_LENGTH] for warning in warnings[:MAX_WARNINGS]]
