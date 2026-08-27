"""The whole command, from a labelled export to a registry entry.

Slow by the standards of this suite and worth it: the pieces are individually
tested, and what this covers is that they compose â€” that a run produces an entry
whose artifact matches its checksum, whose card quotes its own metrics, and whose
plots exist.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from sentinelflow_scoring.features import FEATURE_VERSION
from sentinelflow_scoring.training import registry
from sentinelflow_scoring.training.__main__ import main


def test_a_run_produces_a_loadable_entry(export: Path, tmp_path: Path) -> None:
    models = tmp_path / "models"

    exit_code = main(
        [
            "--dataset",
            str(export),
            "--models",
            str(models),
            "--docs",
            str(tmp_path / "docs"),
            "--lock",
            str(tmp_path / "absent.lock"),
        ]
    )

    assert exit_code == 0
    entry = models / "logistic-regression" / FEATURE_VERSION
    assert entry.is_dir()

    estimator, manifest = registry.load(entry, FEATURE_VERSION)
    assert estimator is not None
    assert manifest.feature_version == FEATURE_VERSION
    assert manifest.dataset_sha256 == "1" * 64
    assert manifest.dataset_generator_version == "1.1.0"
    # The lock was absent, and that is recorded rather than silently omitted.
    assert manifest.environment_lock_sha256 == "lock-file-absent"


def test_the_entry_holds_everything_a_reader_needs(export: Path, tmp_path: Path) -> None:
    models = tmp_path / "models"
    main(["--dataset", str(export), "--models", str(models), "--docs", str(tmp_path / "docs")])
    entry = models / "logistic-regression" / FEATURE_VERSION

    for expected in ("model.joblib", "manifest.json", "metrics.json", "model_card.md"):
        assert (entry / expected).is_file(), expected
    assert (entry / "plots" / "precision-recall.png").stat().st_size > 0
    assert (entry / "plots" / "reliability.png").stat().st_size > 0


def test_the_card_quotes_the_run_that_produced_it(export: Path, tmp_path: Path) -> None:
    models = tmp_path / "models"
    main(["--dataset", str(export), "--models", str(models), "--docs", str(tmp_path / "docs")])
    entry = models / "logistic-regression" / FEATURE_VERSION

    card = (entry / "model_card.md").read_text(encoding="utf-8")
    metrics = json.loads((entry / "metrics.json").read_text(encoding="utf-8"))
    selected = metrics["candidates"]["logistic-regression"]["holdout"]

    # Generated from the result rather than written by hand, so the card and the
    # metrics cannot drift apart.
    assert f"{selected['pr_auc']:.4f}" in card
    assert metrics["selectionReason"] in card

    # The claims this project must never let a card omit.
    assert "synthetic" in card.lower()
    assert "not intended" in card.lower()
    assert "Accuracy is not reported" in card


def test_the_operating_point_in_the_card_is_usable(export: Path, tmp_path: Path) -> None:
    """Enough significant figures to alert on something.

    An earlier version printed `100.00` for a threshold of `99.99986221`, and a
    reader applying `100.00` would have alerted on nothing at all.
    """
    models = tmp_path / "models"
    main(["--dataset", str(export), "--models", str(models), "--docs", str(tmp_path / "docs")])
    entry = models / "logistic-regression" / FEATURE_VERSION

    card = (entry / "model_card.md").read_text(encoding="utf-8")
    manifest = json.loads((entry / "manifest.json").read_text(encoding="utf-8"))

    assert f"{manifest['operating_point']:.10g}" in card


def test_a_missing_dataset_exits_two_without_a_traceback(tmp_path: Path) -> None:
    assert (
        main(
            [
                "--dataset",
                str(tmp_path / "nothing"),
                "--models",
                str(tmp_path / "models"),
                "--docs",
                str(tmp_path / "docs"),
            ]
        )
        == 2
    )


def test_nothing_is_written_when_nothing_is_promoted(
    export: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """The "rules ship alone" outcome leaves no registry entry.

    Writing one would imply a model was promoted, which is the opposite of what
    the run concluded.
    """
    import sentinelflow_scoring.training.pipeline as pipeline

    monkeypatch.setattr(pipeline, "MINIMUM_HOLDOUT_POSITIVES", 10_000_000)
    models = tmp_path / "models"

    exit_code = main(
        ["--dataset", str(export), "--models", str(models), "--docs", str(tmp_path / "docs")]
    )

    assert exit_code == 1
    assert not models.exists()


def test_the_model_version_defaults_to_the_feature_version(export: Path, tmp_path: Path) -> None:
    models = tmp_path / "models"
    main(
        [
            "--dataset",
            str(export),
            "--models",
            str(models),
            "--docs",
            str(tmp_path / "docs"),
            "--model-version",
            "9.9.9",
        ]
    )

    assert (models / "logistic-regression" / "9.9.9").is_dir()
    assert not (models / "logistic-regression" / FEATURE_VERSION).exists()


def test_the_card_is_published_where_it_is_told_to_be(export: Path, tmp_path: Path) -> None:
    """``--docs`` defaults to the repository's own ``docs/ml/``.

    Every run in this file passes it, and this asserts why. Without it the suite
    overwrote the published model card with a card for the TEST fixture — 1,280
    examples where the real one has 20,707 — and it went unnoticed because the
    file is generated, and a regenerated generated file looks exactly like one.

    A test suite that edits the repository it is testing is a defect regardless of
    what it writes.
    """
    published = tmp_path / "docs"

    main(["--dataset", str(export), "--models", str(tmp_path / "m"), "--docs", str(published)])

    assert (published / "MODEL_CARD.md").is_file()
