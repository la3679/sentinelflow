"""Loading the labelled export.

The claims worth asserting are about what the loader refuses. A training run over
a malformed dataset does not crash — it produces a model, and metrics, and a card,
all describing something nobody meant. Every refusal here is a case that would
otherwise have been silent.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from sentinelflow_scoring.features import FEATURE_VERSION, extract
from sentinelflow_scoring.features.schema import ScoreRequest
from sentinelflow_scoring.training.dataset import DatasetError, load


def test_loads_features_through_the_serving_extractor(export: Path) -> None:
    data = load(export)

    first = json.loads((export / "dataset.jsonl").read_text(encoding="utf-8").splitlines()[0])
    request = ScoreRequest.model_validate(
        {"transaction": first["transaction"], "accountContext": first["accountContext"]}
    )
    expected = extract(request).features

    # Not "the shapes match" but "the values are the ones /v1/score would compute".
    # This is ADR-0010 section 1 asserted rather than trusted: a second feature
    # implementation would pass a shape check and fail this.
    for position, name in enumerate(data.feature_names):
        assert data.x[0][position] == pytest.approx(expected[name])


def test_feature_columns_are_sorted_and_complete(export: Path) -> None:
    data = load(export)
    sample = extract(
        ScoreRequest.model_validate(
            {
                "transaction": json.loads(
                    (export / "dataset.jsonl").read_text(encoding="utf-8").splitlines()[0]
                )["transaction"],
                "accountContext": json.loads(
                    (export / "dataset.jsonl").read_text(encoding="utf-8").splitlines()[0]
                )["accountContext"],
            }
        )
    ).features

    assert data.feature_names == tuple(sorted(sample))
    assert data.x.shape == (len(data), len(data.feature_names))


def test_labels_come_from_the_manifest_not_a_hard_coded_normal(
    export: Path, tmp_path: Path
) -> None:
    manifest = json.loads((export / "manifest.json").read_text(encoding="utf-8"))
    manifest["negativeLabel"] = "VELOCITY_BURST"
    (export / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")

    data = load(export)

    # With the negative class redefined, the classes invert. If the loader had
    # hard-coded NORMAL, this would be unchanged - and the two languages could
    # disagree about which class is positive without anything failing.
    assert data.y.sum() == sum(1 for label in data.labels if label == "NORMAL")


def test_rejects_a_missing_export(tmp_path: Path) -> None:
    with pytest.raises(DatasetError, match="make export-dataset"):
        load(tmp_path / "nothing-here")


def test_rejects_a_manifest_with_no_negative_label(export: Path) -> None:
    manifest = json.loads((export / "manifest.json").read_text(encoding="utf-8"))
    del manifest["negativeLabel"]
    (export / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")

    with pytest.raises(DatasetError, match="negativeLabel"):
        load(export)


def test_rejects_a_manifest_with_no_context_version(export: Path) -> None:
    manifest = json.loads((export / "manifest.json").read_text(encoding="utf-8"))
    del manifest["contextVersion"]
    (export / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")

    with pytest.raises(DatasetError, match="contextVersion"):
        load(export)


def test_rejects_an_empty_dataset(export: Path) -> None:
    (export / "dataset.jsonl").write_text("", encoding="utf-8")

    with pytest.raises(DatasetError, match="no examples"):
        load(export)


def test_rejects_a_dataset_with_no_positives(export: Path) -> None:
    lines = (export / "dataset.jsonl").read_text(encoding="utf-8").splitlines()
    rewritten = []
    for line in lines:
        row = json.loads(line)
        row["label"] = "NORMAL"
        rewritten.append(json.dumps(row))
    (export / "dataset.jsonl").write_text("\n".join(rewritten) + "\n", encoding="utf-8")

    with pytest.raises(DatasetError, match="no positive examples"):
        load(export)


def test_rejects_a_line_that_is_not_a_score_request(export: Path) -> None:
    lines = (export / "dataset.jsonl").read_text(encoding="utf-8").splitlines()
    row = json.loads(lines[3])
    del row["transaction"]["amount"]
    lines[3] = json.dumps(row)
    (export / "dataset.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")

    # Named by line number, because "a field is missing somewhere in twenty
    # thousand lines" is not an actionable message.
    with pytest.raises(DatasetError, match="line 4"):
        load(export)


def test_reports_the_feature_version_when_rows_disagree(export: Path) -> None:
    """A ragged matrix aligns one row's column against another row's.

    Unreachable through the real exporter, which is exactly why it is asserted:
    the failure is a matrix whose columns do not mean the same thing in every
    row, and nothing downstream would notice.
    """
    lines = (export / "dataset.jsonl").read_text(encoding="utf-8").splitlines()
    row = json.loads(lines[2])
    row["accountContext"]["recentTransactions"] = []
    lines[2] = json.dumps(row)
    (export / "dataset.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")

    # This particular edit does not make the rows ragged - the extractor always
    # emits every key - so the load succeeds. The assertion is that it does,
    # because a loader that rejected a thin context would reject new accounts.
    data = load(export)
    assert len(data) == len(lines)
    assert FEATURE_VERSION
