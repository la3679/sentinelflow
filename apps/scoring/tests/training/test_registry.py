"""Writing and loading a registry entry.

Every assertion here is about a refusal. An entry that loads when it should not
produces confident, wrong, unattributable scores — the artifact on disk differs
from the one the metrics beside it describe, and nothing in the response says so.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, cast

import pytest
from sklearn.linear_model import LogisticRegression

from sentinelflow_scoring.training import registry
from sentinelflow_scoring.training.registry import MAX_ARTIFACT_BYTES, RegistryError

FEATURE_VERSION = "1.0.0"


def _fitted() -> LogisticRegression:
    model = LogisticRegression()
    model.fit([[0.0], [1.0], [0.0], [1.0]], [0, 1, 0, 1])
    return model


def _manifest(**overrides: object) -> dict[str, Any]:
    base = {
        "model_name": "logistic-regression",
        "model_version": "1.0.0",
        "algorithm": "test",
        "feature_version": FEATURE_VERSION,
        "context_version": 1,
        "trained_at": registry.now_iso(),
        "seed": 20260826,
        "hyperparameters": {"C": "1.0"},
        "feature_names": ["amount"],
        "dataset_sha256": "a" * 64,
        "dataset_generator_version": "1.1.0",
        "dataset_seed": 20260826,
        "dataset_profile": "TEST",
        "dataset_examples": 4,
        "split_strategy": "test",
        "holdout_cutoff": "2026-08-19T00:00:00",
        "operating_point": 99.9,
        "alert_budget": 0.01,
        "environment_lock_sha256": "b" * 64,
    }
    base.update(overrides)
    return base


def test_a_written_entry_loads_back(tmp_path: Path) -> None:
    directory = tmp_path / "entry"
    written = registry.write(directory, _fitted(), _manifest(), {"pr_auc": 0.9}, "# card\n")

    estimator, manifest = registry.load(directory, FEATURE_VERSION)

    assert manifest.artifact_sha256 == written.artifact_sha256
    assert manifest.feature_names == ["amount"]
    # `load` returns `object`, because the registry has no business knowing
    # which estimator class an entry holds. The cast is the caller's job.
    assert cast(LogisticRegression, estimator).predict([[1.0]])[0] == 1


def test_the_checksum_is_over_the_bytes_on_disk(tmp_path: Path) -> None:
    directory = tmp_path / "entry"
    written = registry.write(directory, _fitted(), _manifest(), {}, "# card\n")

    assert written.artifact_sha256 == registry.sha256_of(directory / "model.joblib")


def test_a_tampered_artifact_is_refused(tmp_path: Path) -> None:
    directory = tmp_path / "entry"
    registry.write(directory, _fitted(), _manifest(), {}, "# card\n")
    (directory / "model.joblib").write_bytes(b"not the model that was evaluated")

    # The artifact on disk is not the one the metrics beside it describe. Loading
    # it anyway would serve a model nobody measured.
    with pytest.raises(RegistryError, match="not the one that was evaluated"):
        registry.load(directory, FEATURE_VERSION)


def test_a_feature_version_mismatch_is_refused(tmp_path: Path) -> None:
    directory = tmp_path / "entry"
    registry.write(directory, _fitted(), _manifest(feature_version="0.9.0"), {}, "# card\n")

    # The columns would still line up by position and the scores would still look
    # reasonable, which is exactly why this is a refusal rather than a warning.
    with pytest.raises(RegistryError, match="feature version"):
        registry.load(directory, FEATURE_VERSION)


def test_an_absent_entry_is_refused(tmp_path: Path) -> None:
    with pytest.raises(RegistryError, match="does not exist"):
        registry.load(tmp_path / "nothing", FEATURE_VERSION)


def test_an_oversized_artifact_is_refused_and_not_left_behind(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(registry, "MAX_ARTIFACT_BYTES", 10)
    directory = tmp_path / "entry"

    with pytest.raises(RegistryError, match="ceiling"):
        registry.write(directory, _fitted(), _manifest(), {}, "# card\n")

    # Removed rather than left: ADR-0010 section 6 commits artifacts to the
    # repository, and an over-ceiling file sitting there is one somebody commits.
    assert not (directory / "model.joblib").exists()
    assert MAX_ARTIFACT_BYTES > 10


def test_json_is_written_deterministically(tmp_path: Path) -> None:
    """Two runs with the same content produce the same bytes.

    Not cosmetic: the manifest and metrics are reviewed in a diff, and key order
    that varied between runs would make every retrain look like a change.
    """
    first, second = tmp_path / "a", tmp_path / "b"
    fixed = _manifest(trained_at="2026-08-26T12:00:00Z")
    registry.write(first, _fitted(), fixed, {"b": 2, "a": 1}, "# card\n")
    registry.write(second, _fitted(), fixed, {"a": 1, "b": 2}, "# card\n")

    assert (first / "metrics.json").read_text(encoding="utf-8") == (
        second / "metrics.json"
    ).read_text(encoding="utf-8")
    assert json.loads((first / "metrics.json").read_text(encoding="utf-8")) == {"a": 1, "b": 2}


def test_sha256_of_text_and_now_iso_are_usable(tmp_path: Path) -> None:
    assert len(registry.sha256_of_text("x")) == 64
    assert registry.now_iso().endswith("Z")
    assert registry.entry_directory(tmp_path, "name", "1.0.0") == tmp_path / "name" / "1.0.0"
