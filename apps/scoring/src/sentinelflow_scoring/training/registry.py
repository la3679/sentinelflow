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
from collections.abc import Sequence
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


def discover(root: Path, feature_version: str) -> Path | None:
    """The one entry this build can serve, or ``None`` if there is none.

    A registry root can hold several entries — a previous algorithm, a previous
    feature version — because ADR-0010 §6 commits them and nothing prunes them.
    Serving needs exactly one, and the rule is stated here rather than left to
    whichever order the filesystem happens to return:

    - An entry fitted on a **different feature version is not a candidate**. It is
      history: this build cannot compute the columns it was fitted on, so it
      could never be served, and skipping it is not a choice between models.
    - Exactly one match is the answer.
    - **More than one match is a refusal, not a tie-break.** Two entries at the
      running feature version are two defensible answers, and picking by name or
      by mtime would make which model produced a score depend on something no
      operator declared. Pin one with ``SENTINELFLOW_SCORING_MODEL_NAME`` and
      ``SENTINELFLOW_SCORING_MODEL_VERSION``, or remove the superseded entry.
    - No match at all is ``None`` rather than an error: a service with no model is
      a state the contract has a response for, and the API degrades to rules.

    :raises RegistryError: if a manifest cannot be read, or if more than one entry
        matches.
    """
    if not root.is_dir():
        return None

    matches: list[Path] = []
    for manifest_file in sorted(root.glob("*/*/manifest.json")):
        try:
            payload = json.loads(manifest_file.read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            raise RegistryError(
                f"{manifest_file} could not be read as a manifest: {error}. Every directory "
                "under the registry root is this project's own output; a malformed one is a "
                "corrupted entry rather than something to skip past."
            ) from error
        if payload.get("feature_version") == feature_version:
            matches.append(manifest_file.parent)

    if not matches:
        return None
    if len(matches) > 1:
        listed = ", ".join(str(match) for match in matches)
        raise RegistryError(
            f"{len(matches)} registry entries are fitted on feature version {feature_version}: "
            f"{listed}. Which one serves a request would otherwise depend on directory order. "
            "Pin one with SENTINELFLOW_SCORING_MODEL_NAME and SENTINELFLOW_SCORING_MODEL_VERSION, "
            "or remove the superseded entry."
        )
    return matches[0]


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


def load(
    directory: Path,
    expected_feature_version: str,
    *,
    expected_feature_names: Sequence[str],
) -> tuple[object, ModelManifest]:
    """Loads an entry after validating it.

    :param expected_feature_names: the column order the caller is about to supply,
        which is :data:`sentinelflow_scoring.features.FEATURE_NAMES` on the
        request path. Required rather than optional: a check that has to be asked
        for is one that will eventually not be, and this is the check whose
        absence has no symptom.

    :raises RegistryError: if the entry is absent, the checksum does not match,
        the feature version differs from the running build, or the recorded
        column order is not the one the caller supplies. All four are refusals
        rather than warnings: a model served against features it was not fitted on
        produces confident, wrong, unattributable scores.
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

    if list(manifest.feature_names) != list(expected_feature_names):
        raise RegistryError(
            f"{directory} was fitted on columns {manifest.feature_names}; this caller supplies "
            f"{list(expected_feature_names)}. A model handed its columns in a different order is "
            "not a broken model — it is one quietly answering about different quantities, and "
            "nothing downstream would ever notice."
        )

    # Only the project's own artifacts are ever loaded, which is what makes this
    # acceptable: joblib.load executes pickled constructors, so an untrusted file
    # here would be arbitrary code execution. The checksum above is the control,
    # and docs/security/THREAT_MODEL.md records it.
    return joblib.load(artifact), manifest


def read_metrics(directory: Path) -> dict[str, Any]:
    """The metrics document beside an artifact.

    :raises RegistryError: if it is absent or unreadable. An entry without its
        metrics is not a lighter entry — it is one whose ``/v1/model`` answer
        would have to be invented.
    """
    path = directory / "metrics.json"
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise RegistryError(f"{path} could not be read as a metrics document: {error}") from error
    if not isinstance(payload, dict):
        raise RegistryError(f"{path} is not a JSON object")
    return payload


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
