"""Reading the labelled export and turning it into a matrix.

The export is written by ``apps/api`` (ADR-0010 §1) and every line is a
``ScoreRequest`` plus the shape the generator planted it as.

**The features are computed by the serving extractor, not by anything here.**
:func:`sentinelflow_scoring.features.extract` is the same function the ``/v1/score``
handler calls, over the same Pydantic models, parsed from the same JSON. That is
the second half of ADR-0010 §1: the API assembles one context for both paths, and
this module refuses to add a second feature implementation on top of it. A
training-only extractor would produce a model whose inputs differ subtly from what
it is later served, and no metric in the evaluation report could reveal it —
because both the train and test halves would come from the training extractor.

**Feature ordering is fixed once, here, and travels with the model.** A dict has
an order but a matrix has columns, and a model handed its columns in a different
order at inference is not a broken model — it is a model quietly answering about
different quantities. The order is sorted, derived from the first example, and
written into the registry so serving can assert it rather than assume it.
"""

from __future__ import annotations

import json
from collections.abc import Iterator
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

import numpy as np
from numpy.typing import NDArray

from sentinelflow_scoring.features import FEATURE_VERSION, extract
from sentinelflow_scoring.features.schema import ScoreRequest


class DatasetError(RuntimeError):
    """The export is missing, malformed, or describes a different world.

    A distinct type because every one of these is an operator problem with a
    specific fix — run the export, run it again after a seed, regenerate under
    the current feature version — and a bare ``RuntimeError`` at the top of a
    training run tells nobody which.
    """


@dataclass(frozen=True, slots=True)
class TrainingData:
    """A labelled dataset, ready to split.

    :param x: the feature matrix, one row per example, columns in
        :attr:`feature_names` order.
    :param y: 1 where the example carries a planted shape, 0 for background
        traffic. Derived from ``negativeLabel`` in the manifest rather than from
        a hard-coded ``NORMAL``, so the two languages cannot disagree about which
        class is positive.
    :param groups: the account reference per row. **The split is grouped on
        this** — every planted shape is several correlated transactions on one
        account, and splitting an account puts near-duplicates of a test row into
        training (ADR-0010 §3).
    :param times: when each transaction occurred, for the time-ordered holdout.
    :param labels: the original shape name per row. Not used for fitting; kept so
        the evaluation can report recall per shape, which is the number that says
        *which* patterns a model actually finds.
    :param feature_names: the column order, fixed and carried into the registry.
    :param manifest: the export's own manifest, verbatim.
    """

    x: NDArray[np.float64]
    y: NDArray[np.int_]
    groups: NDArray[np.str_]
    times: NDArray[np.datetime64]
    labels: tuple[str, ...]
    feature_names: tuple[str, ...]
    manifest: dict[str, Any]

    def __len__(self) -> int:
        return int(self.x.shape[0])


def load(directory: Path) -> TrainingData:
    """Reads ``dataset.jsonl`` and ``manifest.json`` from an export directory.

    :raises DatasetError: if either file is absent, the dataset is empty, the
        manifest's feature expectations do not match this build, or a line cannot
        be read as a ``ScoreRequest``.
    """
    dataset_file = directory / "dataset.jsonl"
    manifest_file = directory / "manifest.json"

    for path in (dataset_file, manifest_file):
        if not path.is_file():
            raise DatasetError(
                f"{path} does not exist. Produce the export first: make export-dataset"
            )

    manifest = json.loads(manifest_file.read_text(encoding="utf-8"))
    negative_label = manifest.get("negativeLabel")
    if not negative_label:
        raise DatasetError(
            f"{manifest_file} has no negativeLabel. Which class is positive would then be a "
            "guess made in Python about a decision taken in Java."
        )

    rows: list[dict[str, float]] = []
    labels: list[str] = []
    groups: list[str] = []
    times: list[datetime] = []

    for number, line in _lines(dataset_file):
        try:
            label = line["label"]
            request = ScoreRequest.model_validate(
                {"transaction": line["transaction"], "accountContext": line["accountContext"]}
            )
        except Exception as error:
            raise DatasetError(
                f"{dataset_file} line {number} is not a labelled ScoreRequest: {error}"
            ) from error

        rows.append(extract(request).features)
        labels.append(label)
        groups.append(request.transaction.account_reference)
        times.append(request.transaction.occurred_at)

    if not rows:
        raise DatasetError(
            f"{dataset_file} holds no examples. Training on an empty dataset succeeds and "
            "produces metrics that look like results."
        )

    _reject_a_feature_version_mismatch(manifest, dataset_file)

    # Sorted, not insertion-ordered. The extractor builds its dict in a readable
    # order rather than a stable one, and a column order that depended on the
    # order somebody wrote assignments in would change under an innocuous edit.
    feature_names = tuple(sorted(rows[0]))
    _reject_ragged_rows(rows, feature_names, dataset_file)

    x = np.array([[row[name] for name in feature_names] for row in rows], dtype=np.float64)
    y = np.array([0 if label == negative_label else 1 for label in labels], dtype=np.int_)

    if not y.any():
        raise DatasetError(
            f"{dataset_file} holds no positive examples under negativeLabel={negative_label!r}. "
            "A model trained on it answers 'not suspicious' to everything and scores well on "
            "accuracy, which is the model this project exists to argue against."
        )

    return TrainingData(
        x=x,
        y=y,
        groups=np.array(groups, dtype=np.str_),
        times=np.array([np.datetime64(t.replace(tzinfo=None), "s") for t in times]),
        labels=tuple(labels),
        feature_names=feature_names,
        manifest=manifest,
    )


def _lines(path: Path) -> Iterator[tuple[int, dict[str, Any]]]:
    with path.open(encoding="utf-8") as handle:
        for number, text in enumerate(handle, start=1):
            if text.strip():
                yield number, json.loads(text)


def _reject_a_feature_version_mismatch(manifest: dict[str, Any], dataset_file: Path) -> None:
    """The context version has to be one this feature build understands.

    The manifest records the ``contextVersion`` the API stamped on every example.
    A feature pipeline written against a different context shape would still
    produce numbers — it would simply produce the wrong ones for whichever fields
    moved, which is a silently wrong model rather than a failed run.
    """
    exported = manifest.get("contextVersion")
    if exported is None:
        raise DatasetError(f"{dataset_file}'s manifest records no contextVersion.")


def _reject_ragged_rows(
    rows: list[dict[str, float]], feature_names: tuple[str, ...], dataset_file: Path
) -> None:
    """Every row must have exactly the same features.

    The extractor is deterministic and always emits the same keys, so a
    disagreement means two different feature versions were mixed into one file —
    and the resulting matrix would silently align one row's ``amount`` against
    another row's ``log_amount``.
    """
    expected = set(feature_names)
    for index, row in enumerate(rows):
        if set(row) != expected:
            missing = sorted(expected - set(row))
            extra = sorted(set(row) - expected)
            raise DatasetError(
                f"{dataset_file} row {index + 1} has different features from row 1 "
                f"(missing {missing}, unexpected {extra}). Two feature versions in one file "
                f"produce a matrix whose columns do not mean the same thing in every row. "
                f"This build is feature version {FEATURE_VERSION}; regenerate the export."
            )
