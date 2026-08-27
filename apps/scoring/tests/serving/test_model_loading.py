"""Which entry a process serves, and what it refuses to serve.

Four of these are refusals. That is the point: the failure this module exists to
prevent — a model answering about different quantities than the caller thinks it
is — produces no error, no warning and a plausible number, so the only place it
can be caught is before the model is used.
"""

from __future__ import annotations

import json
import shutil
from pathlib import Path

import pytest
from sklearn.linear_model import LogisticRegression

from sentinelflow_scoring.app import create_app
from sentinelflow_scoring.config import Settings
from sentinelflow_scoring.features import FEATURE_NAMES, FEATURE_VERSION
from sentinelflow_scoring.serving.model import load_active
from sentinelflow_scoring.training import registry
from sentinelflow_scoring.training.registry import RegistryError
from tests.serving.conftest import REGISTRY_ROOT, settings_for


def copy_entry(destination: Path, *, name: str = "logistic-regression") -> Path:
    """The committed entry, copied under a chosen model name."""
    source = next(REGISTRY_ROOT.glob("*/*/manifest.json")).parent
    target = destination / name / source.name
    shutil.copytree(source, target)
    if name != source.parent.name:
        manifest = json.loads((target / "manifest.json").read_text(encoding="utf-8"))
        original = manifest["model_name"]
        manifest["model_name"] = name
        (target / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
        # The metrics document is renamed with it. An entry whose metrics name a
        # different model is refused, which is its own test below rather than an
        # accident of this helper.
        metrics = json.loads((target / "metrics.json").read_text(encoding="utf-8"))
        metrics["selected"] = name
        metrics["candidates"][name] = metrics["candidates"].pop(original)
        (target / "metrics.json").write_text(json.dumps(metrics, indent=2), encoding="utf-8")
    return target


def test_the_committed_entry_loads(tmp_path: Path) -> None:
    active = load_active(settings_for(REGISTRY_ROOT))

    assert active is not None
    assert active.manifest.feature_version == FEATURE_VERSION
    assert list(active.manifest.feature_names) == list(FEATURE_NAMES)


def test_an_empty_registry_is_not_an_error(tmp_path: Path) -> None:
    """A clone that has never run ``make train`` is a state the contract has a
    response for, and the API degrades to rules. Refusing to start would turn a
    designed degradation into an outage."""
    empty = tmp_path / "models"
    empty.mkdir()

    assert load_active(settings_for(empty)) is None


def test_a_missing_registry_root_is_not_an_error(tmp_path: Path) -> None:
    assert load_active(settings_for(tmp_path / "never-created")) is None


def test_two_entries_at_the_running_feature_version_are_refused(tmp_path: Path) -> None:
    """Not a tie-break. Two defensible answers mean which model produced a score
    would depend on directory order, which no operator declared."""
    root = tmp_path / "models"
    copy_entry(root)
    copy_entry(root, name="hist-gradient-boosting")

    with pytest.raises(RegistryError, match="registry entries are fitted on feature version"):
        load_active(settings_for(root))


def test_an_entry_from_another_feature_version_is_history_not_a_candidate(tmp_path: Path) -> None:
    """This build cannot compute the columns it was fitted on, so it could never
    be served — skipping it is not a choice between models."""
    root = tmp_path / "models"
    stale = copy_entry(root, name="older-model")
    manifest = json.loads((stale / "manifest.json").read_text(encoding="utf-8"))
    manifest["feature_version"] = "0.9.0"
    (stale / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    copy_entry(root)

    active = load_active(settings_for(root))

    assert active is not None
    assert active.manifest.model_name == "logistic-regression"


def test_a_pin_selects_one_of_two_entries(tmp_path: Path) -> None:
    root = tmp_path / "models"
    copy_entry(root)
    copy_entry(root, name="hist-gradient-boosting")
    version = next(root.glob("*/*/manifest.json")).parent.name

    active = load_active(
        Settings(
            models_root=root,
            model_name="hist-gradient-boosting",
            model_version=version,
        )
    )

    assert active is not None
    assert active.manifest.model_name == "hist-gradient-boosting"


def test_a_pin_at_a_directory_that_does_not_exist_is_refused(tmp_path: Path) -> None:
    """A pin that fell back to discovery would serve a model nobody asked for."""
    root = tmp_path / "models"
    copy_entry(root)

    with pytest.raises(RegistryError, match="pinned by configuration"):
        load_active(Settings(models_root=root, model_name="absent", model_version="1.0.0"))


def test_half_a_pin_is_rejected_at_configuration_time(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="set together or not at all"):
        Settings(models_root=tmp_path, model_name="logistic-regression")


def test_a_tampered_artifact_stops_the_process_rather_than_serving(tmp_path: Path) -> None:
    """A checksum mismatch is a corrupted or substituted artifact. Serving around
    it quietly is the one behaviour with no defensible reading."""
    root = tmp_path / "models"
    entry = copy_entry(root)
    (entry / "model.joblib").write_bytes(b"not the model that was evaluated")

    with pytest.raises(RegistryError, match="not the one that was evaluated"):
        create_app(settings_for(root))


def test_a_reordered_column_list_is_refused(tmp_path: Path) -> None:
    """The failure with no symptom.

    A model handed its columns in a different order still returns a number, still
    between 0 and 100, and it is an answer about different quantities. Nothing
    downstream would ever notice, which is why the check is here.
    """
    root = tmp_path / "models"
    entry = copy_entry(root)
    manifest = json.loads((entry / "manifest.json").read_text(encoding="utf-8"))
    manifest["feature_names"] = list(reversed(manifest["feature_names"]))
    (entry / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")

    with pytest.raises(RegistryError, match="different order"):
        load_active(settings_for(root))


def test_metrics_describing_another_model_are_refused(tmp_path: Path) -> None:
    """``/v1/model`` publishes these figures. A manifest that cannot be matched to
    its own metrics is an entry that cannot honestly answer it."""
    root = tmp_path / "models"
    entry = copy_entry(root)
    metrics = json.loads((entry / "metrics.json").read_text(encoding="utf-8"))
    metrics["selected"] = "some-other-model"
    (entry / "metrics.json").write_text(json.dumps(metrics, indent=2), encoding="utf-8")

    with pytest.raises(RegistryError, match="selected model"):
        load_active(settings_for(root))


def test_a_malformed_manifest_is_not_skipped_past(tmp_path: Path) -> None:
    """Every directory under the registry root is this project's own output."""
    root = tmp_path / "models"
    entry = copy_entry(root)
    (entry / "manifest.json").write_text("{ not json", encoding="utf-8")

    with pytest.raises(RegistryError, match="could not be read as a manifest"):
        load_active(settings_for(root))


def test_an_entry_whose_estimator_is_not_a_pipeline_still_scores(tmp_path: Path) -> None:
    """It just cannot be taken apart, and the response says so rather than
    inventing reasons. An invented explanation is worse than an absent one,
    because only one of the two is visibly missing."""
    from fastapi.testclient import TestClient

    from sentinelflow_scoring.serving import reasons
    from tests.serving.conftest import payload

    root = tmp_path / "models"
    directory = root / "bare-estimator" / FEATURE_VERSION
    estimator = LogisticRegression(max_iter=1000)
    estimator.fit(
        [[float(index) for index in range(len(FEATURE_NAMES))], [0.0] * len(FEATURE_NAMES)],
        [1, 0],
    )
    registry.write(
        directory,
        estimator,
        _manifest_for(list(FEATURE_NAMES)),
        {"selected": "bare-estimator", "candidates": {"bare-estimator": {"holdout": _holdout()}}},
        "# card\n",
    )

    with TestClient(create_app(settings_for(root))) as client:
        body = client.post("/v1/score", json=payload()).json()

    assert body["reasons"] == []
    assert reasons.NO_ATTRIBUTION_WARNING in body["warnings"]


def _manifest_for(feature_names: list[str]) -> dict[str, object]:
    return {
        "model_name": "bare-estimator",
        "model_version": FEATURE_VERSION,
        "algorithm": "A bare estimator, for the no-attribution path.",
        "feature_version": FEATURE_VERSION,
        "context_version": 1,
        "trained_at": "2026-08-27T00:00:00Z",
        "seed": 1,
        "hyperparameters": {},
        "feature_names": feature_names,
        "dataset_sha256": "a" * 64,
        "dataset_generator_version": "1.1.0",
        "dataset_seed": 1,
        "dataset_profile": "TEST",
        "dataset_examples": 2,
        "split_strategy": "test",
        "holdout_cutoff": "2026-08-19T00:00:00",
        "operating_point": 50.0,
        "alert_budget": 0.01,
        "environment_lock_sha256": "b" * 64,
    }


def _holdout() -> dict[str, float | int]:
    return {
        "precision": 1.0,
        "recall": 0.5,
        "f1": 0.6666666666666666,
        "pr_auc": 0.75,
        "roc_auc": 0.8,
        "false_positive_rate": 0.0,
        "threshold": 50.0,
        "alert_count": 1,
    }


def test_an_entry_with_no_holdout_metrics_is_refused(tmp_path: Path) -> None:
    """A model whose measurements cannot be found is not one to serve, because
    ``/v1/model`` would have nothing to publish."""
    root = tmp_path / "models"
    entry = copy_entry(root)
    metrics = json.loads((entry / "metrics.json").read_text(encoding="utf-8"))
    metrics["candidates"][metrics["selected"]].pop("holdout")
    (entry / "metrics.json").write_text(json.dumps(metrics, indent=2), encoding="utf-8")

    with pytest.raises(RegistryError, match="no holdout metrics"):
        load_active(settings_for(root))


def test_an_entry_without_a_metrics_document_is_refused(tmp_path: Path) -> None:
    """An entry without its metrics is not a lighter entry — it is one whose
    ``/v1/model`` answer would have to be invented."""
    root = tmp_path / "models"
    entry = copy_entry(root)
    (entry / "metrics.json").unlink()

    with pytest.raises(RegistryError, match="could not be read as a metrics document"):
        load_active(settings_for(root))


def test_a_metrics_document_that_is_not_an_object_is_refused(tmp_path: Path) -> None:
    root = tmp_path / "models"
    entry = copy_entry(root)
    (entry / "metrics.json").write_text("[]", encoding="utf-8")

    with pytest.raises(RegistryError, match="not a JSON object"):
        load_active(settings_for(root))
