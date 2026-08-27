"""The offline training command.

    uv run python -m sentinelflow_scoring.training

**An explicit command, never an API side effect** (§12.6). Training reads a whole
dataset, fits several models and writes files; a service that did any of that in
response to a request would be a service whose behaviour depends on who called it.

It exits non-zero when nothing ships, and that is not an error condition — it is
the pre-registered outcome from ADR-0010 §5 in which no model beat the rules
baseline by the required margin. The distinction is in the message and in the
metrics document, both of which say so plainly. A run that "succeeded" while
promoting nothing would be the confusing one.
"""

from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

from sentinelflow_scoring.features import FEATURE_VERSION
from sentinelflow_scoring.training import card, dataset, registry
from sentinelflow_scoring.training.pipeline import train, write_entry

#: Where the labelled export lands, relative to the repository root. Matches the
#: bind mount in compose.yaml and the default in the API's own configuration.
DEFAULT_DATASET = Path("../../data/generated/training")

#: The registry root. ADR-0010 §6 commits entries here so a demo can score
#: without someone running a training job first.
DEFAULT_MODELS = Path("models")

#: The environment lock, hashed into the manifest. Two runs with different
#: resolved dependencies are two different runs even at the same seed.
DEFAULT_LOCK = Path("uv.lock")

#: Where the model card is published for readers, as well as into the registry.
#:
#: **Written by this command rather than maintained by hand.** §12.6 requires a
#: model card in the documentation tree; a hand-written copy of a generated
#: document drifts from the run the moment either changes, and a card that
#: disagrees with its own metrics is worse than none. One generator, two
#: destinations, and the file says at the top that it is generated.
DEFAULT_DOCS = Path("../../docs/ml")

log = logging.getLogger("sentinelflow.training")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="python -m sentinelflow_scoring.training",
        description="Train, evaluate and register a risk model from the labelled export.",
    )
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--models", type=Path, default=DEFAULT_MODELS)
    parser.add_argument("--lock", type=Path, default=DEFAULT_LOCK)
    parser.add_argument(
        "--docs",
        type=Path,
        default=DEFAULT_DOCS,
        help="Where to publish MODEL_CARD.md alongside the registry copy.",
    )
    parser.add_argument(
        "--seed",
        type=int,
        default=20260826,
        help="Fixed and recorded. The same value evaluates on the same accounts.",
    )
    parser.add_argument(
        "--model-version",
        default=None,
        help="Defaults to the feature version, which is the honest default: a model fitted on "
        "different features is a different model even when the algorithm is identical.",
    )
    arguments = parser.parse_args(argv)

    logging.basicConfig(level=logging.INFO, format="%(message)s")

    try:
        data = dataset.load(arguments.dataset)
    except dataset.DatasetError as error:
        log.error("%s", error)
        return 2

    log.info(
        "Loaded %d examples (%d planted) with %d features, feature version %s",
        len(data),
        int(data.y.sum()),
        len(data.feature_names),
        FEATURE_VERSION,
    )

    result = train(data, arguments.seed)

    log.info("Rules baseline PR-AUC %.4f", result.rules.pr_auc)
    for candidate in result.candidates:
        log.info(
            "%-24s PR-AUC %.4f  folds mean %.4f spread %.4f  %s",
            candidate.name,
            candidate.holdout.pr_auc,
            candidate.fold_mean,
            candidate.fold_spread,
            "eligible" if candidate.eligible else "comparison only",
        )
    log.info("%s", result.selection.reason)

    if result.selection.model is None:
        # Deliberately not written to the registry. Promoting nothing is the
        # correct outcome here, and an entry would imply otherwise.
        log.error("No model promoted. The rules baseline ships alone (ADR-0010 §5).")
        return 1

    model_version = arguments.model_version or FEATURE_VERSION
    lock_hash = (
        registry.sha256_of(arguments.lock) if arguments.lock.is_file() else "lock-file-absent"
    )

    model_card = card.render(
        result,
        model_version,
        str(data.manifest.get("profile", "unknown")),
        len(data),
    )

    directory = write_entry(
        result=result,
        data=data,
        root=arguments.models,
        model_version=model_version,
        seed=arguments.seed,
        lock_sha256=lock_hash,
        model_card=model_card,
    )

    published = _publish_card(arguments.docs, model_card)

    log.info("Wrote %s", directory)
    if published is not None:
        log.info("Published %s", published)
    return 0


def _publish_card(docs: Path, model_card: str) -> Path | None:
    """Copies the card into the documentation tree.

    Best effort by design: a training run that succeeded and wrote a valid
    registry entry has done its job, and failing it because a documentation
    directory could not be created would make the model unavailable over a copy.
    The registry copy is the one that matters and it is already written.
    """
    try:
        docs.mkdir(parents=True, exist_ok=True)
        path = docs / "MODEL_CARD.md"
        path.write_text(model_card, encoding="utf-8")
    except OSError as error:
        log.warning("Could not publish the model card to %s: %s", docs, error)
        return None
    return path


if __name__ == "__main__":  # pragma: no cover - exercised by running the command
    sys.exit(main())
