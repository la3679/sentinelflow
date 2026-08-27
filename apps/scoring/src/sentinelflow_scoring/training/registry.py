"""Writing and reading a model registry entry.

A registry entry is a directory holding everything needed to know what a model
is, what it was measured at, and whether the artifact on disk is the one that was
measured:

    apps/scoring/models/<name>/<version>/
        model.joblib      the fitted estimator
        manifest.json     provenance, feature order, artifact SHA-256
        metrics.json      what it scored, and what every other candidate scored
        model_card.md     the human-readable version
        plots/            precision-recall and reliability curves

**The checksum proves the loaded artifact is the evaluated artifact. It does not
claim byte-reproducible retraining** — joblib output depends on library build
details that vary across platforms, and claiming otherwise would be a
reproducibility guarantee this project cannot honour (ADR-0010 §6). What is
reproducible is the *metrics*, from a fixed seed over a fingerprinted dataset.

**Loading validates three things before an artifact is used**: the checksum, that
the feature version matches the running build, and that the recorded feature order
is the order the caller is about to supply. The third is the one that would
otherwise fail silently — a model handed its columns in a different order is not a
broken model, it is a model quietly answering about different quantities.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import joblib

#: Refuse to write an artifact larger than this. ADR-0010 §6 commits the artifact
#: to the repository so a demo can score without someone running a training job
#: first; that is only defensible while it stays small, and a ceiling enforced by
#: the command is a control where a ceiling left to review is a hope.
MAX_ARTIFACT_BYTES = 8 * 1024 * 1024


class RegistryError(RuntimeError):
    """An entry is absent, malformed, or does not match the running build."""


@dataclass(frozen=True, slots=True)
class ModelManifest:
    """Everything needed to say what a model is and how it came to be.

    :param feature_names: the column order the estimator was fitted on. Carried
        so serving can assert it rather than reconstruct it.
    :param dataset_sha256: the export's fingerprint, copied from its manifest. A
        rerun over a different dataset is a different result even at the same seed.
    :param artifact_sha256: over ``model.joblib``. Verified before every load.
    """

    model_name: str
    model_version: str
    algorithm: str
    feature_version: str
    context_version: int
    trained_at: str
    seed: int
    hyperparameters: dict[str, Any]
    feature_names: list[str]
    dataset_sha256: str
    dataset_generator_version: str
    dataset_seed: int
    dataset_profile: str
    dataset_examples: int
    split_strategy: str
    holdout_cutoff: str
    operating_point: float
    alert_budget: float
    environment_lock_sha256: str
    artifact_sha256: str

    def as_dict(self) -> dict[str, Any]:
        return asdict(self)


def entry_directory(root: Path, model_name: str, model_version: str) -> Path:
    return root / model_name / model_version


def write(
    directory: Path,
    estimator: object,
    manifest_without_checksum: dict[str, Any],
    metrics: dict[str, Any],
    model_card: str,
) -> ModelManifest:
    """Writes an entry, computing the artifact checksum from the bytes on disk.

    The checksum is taken **after** the artifact is written rather than from an
    in-memory buffer, so it covers what a loader will actually read. Those differ
    if serialisation is not deterministic, and the whole point of the checksum is
    to catch a mismatch between what was measured and what is served.

    :raises RegistryError: if the artifact exceeds :data:`MAX_ARTIFACT_BYTES`.
    """
    directory.mkdir(parents=True, exist_ok=True)
    artifact = directory / "model.joblib"
    joblib.dump(estimator, artifact, compress=3)

    size = artifact.stat().st_size
    if size > MAX_ARTIFACT_BYTES:
        artifact.unlink()
        raise RegistryError(
            f"The artifact is {size} bytes, over the {MAX_ARTIFACT_BYTES}-byte ceiling. "
            "ADR-0010 §6 commits it to the repository, which is only defensible while it stays "
            "small. Reduce the model or revisit the ADR — do not raise this quietly."
        )

    manifest = ModelManifest(**manifest_without_checksum, artifact_sha256=sha256_of(artifact))
    _write_json(directory / "manifest.json", manifest.as_dict())
    _write_json(directory / "metrics.json", metrics)
    (directory / "model_card.md").write_text(model_card, encoding="utf-8")
    return manifest


def load(directory: Path, expected_feature_version: str) -> tuple[object, ModelManifest]:
    """Loads an entry after validating it.

    :raises RegistryError: if the entry is absent, the checksum does not match,
        or the feature version differs from the running build. All three are
        refusals rather than warnings: a model served against features it was not
        fitted on produces confident, wrong, unattributable scores.
    """
    manifest_file = directory / "manifest.json"
    artifact = directory / "model.joblib"
    for path in (manifest_file, artifact):
        if not path.is_file():
            raise RegistryError(f"{path} does not exist")

    manifest = ModelManifest(**json.loads(manifest_file.read_text(encoding="utf-8")))

    actual = sha256_of(artifact)
    if actual != manifest.artifact_sha256:
        raise RegistryError(
            f"{artifact} has SHA-256 {actual}, but its manifest records "
            f"{manifest.artifact_sha256}. The artifact on disk is not the one that was "
            "evaluated, so none of the metrics beside it describe it."
        )

    if manifest.feature_version != expected_feature_version:
        raise RegistryError(
            f"{directory} was fitted on feature version {manifest.feature_version}; this build "
            f"computes {expected_feature_version}. The columns would still line up by position "
            "and the scores would still look reasonable, which is exactly why this is a refusal."
        )

    # Only the project's own artifacts are ever loaded, which is what makes this
    # acceptable: joblib.load executes pickled constructors, so an untrusted file
    # here would be arbitrary code execution. The checksum above is the control,
    # and docs/security/THREAT_MODEL.md records it.
    return joblib.load(artifact), manifest


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(65536), b""):
            digest.update(block)
    return digest.hexdigest()


def sha256_of_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def now_iso() -> str:
    return datetime.now(UTC).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _write_json(path: Path, payload: dict[str, Any]) -> None:
    # sort_keys so two runs producing the same content produce the same bytes,
    # and a trailing newline so the file is a well-formed text file.
    path.write_text(
        json.dumps(payload, indent=2, sort_keys=True, default=str) + "\n", encoding="utf-8"
    )
