"""The two plots that say something the numbers do not.

Deliberately two, not a gallery. A precision-recall curve shows the trade-off the
operating point was chosen along, and a reliability curve shows whether the
calibration ADR-0008 §4 depends on actually holds. Everything else worth knowing
is a number, and a number belongs in ``metrics.json`` where it can be compared.

**matplotlib is a training-only dependency** (R-2026-08-26-01), so this module is
imported by the training command and by nothing on the serving path. The import
is inside the functions for that reason: importing it at module scope would make
``sentinelflow_scoring.training`` unimportable in the serving image, and something
will eventually import it there by accident.
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
from numpy.typing import NDArray


def precision_recall(
    directory: Path, curves: dict[str, tuple[NDArray[np.int_], NDArray[np.float64]]]
) -> Path:
    """Precision against recall, every candidate on one pair of axes.

    One figure rather than one per model, because the comparison is the point:
    separate plots make two curves look similar that a shared axis shows are not.
    """
    import matplotlib

    # Agg before pyplot: there is no display in CI or in a container, and the
    # default backend would try to find one and fail at import time.
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from sklearn.metrics import average_precision_score, precision_recall_curve

    figure, axes = plt.subplots(figsize=(7, 5))
    for name, (y_true, scores) in sorted(curves.items()):
        precision, recall, _ = precision_recall_curve(y_true, scores)
        average = average_precision_score(y_true, scores)
        axes.plot(recall, precision, label=f"{name} (PR-AUC {average:.3f})")

    # The share of positives: the precision a coin flip would achieve, and the
    # only honest floor for a PR curve. Without it a curve sitting at 0.4
    # precision looks poor when the base rate is 0.02.
    first = next(iter(curves.values()))[0]
    baseline = float(first.mean())
    axes.axhline(
        baseline, linestyle="--", linewidth=1, color="grey", label=f"no-skill ({baseline:.3f})"
    )

    axes.set_xlabel("Recall")
    axes.set_ylabel("Precision")
    axes.set_title("Precision-recall on the held-out set (synthetic data)")
    axes.set_ylim(0.0, 1.02)
    axes.legend(loc="best", fontsize="small")
    figure.tight_layout()

    path = directory / "precision-recall.png"
    figure.savefig(path, dpi=120)
    plt.close(figure)
    return path


def reliability(
    directory: Path, y_true: NDArray[np.int_], scores: NDArray[np.float64], name: str
) -> Path:
    """Predicted probability against observed frequency, for the selected model.

    This is the plot that justifies ADR-0008 §4. A threshold on the contract's
    0-to-100 scale only means the same thing across model versions if the
    underlying quantity is calibrated, and a reliability curve is how that claim
    is checked rather than asserted.
    """
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from sklearn.calibration import calibration_curve

    probability = np.clip(scores / 100.0, 0.0, 1.0)

    # **Uniform bins, not quantile bins**, and that is the whole usefulness of
    # this plot. Under quantile binning almost every bin lands in the crowded
    # region near zero — 97% of transactions score there — and the curve collapses
    # into the bottom-left corner saying nothing at all about the top of the
    # scale, which is the only region the operating point is ever in. The first
    # version of this plot did exactly that.
    #
    # Uniform bins put a boundary every 0.1, so the alerting region gets a bin of
    # its own. `calibration_curve` returns only the non-empty ones, so the sparse
    # middle simply does not appear rather than appearing as noise.
    observed, predicted = calibration_curve(y_true, probability, n_bins=10, strategy="uniform")

    # How many rows each plotted bin holds. **Without this the plot lies by
    # omission**: the middle bins hold a handful of transactions each and swing
    # between 0.0 and 1.0 on one example, which reads as wild mis-calibration
    # next to bins holding thousands. Sizing each marker by its bin count makes
    # "this point is noise" visible instead of something a reader has to know.
    counts, _ = np.histogram(probability, bins=np.linspace(0.0, 1.0, 11))
    populated = counts[counts > 0]

    figure, axes = plt.subplots(figsize=(6.5, 5))
    axes.plot([0, 1], [0, 1], linestyle="--", linewidth=1, color="grey", label="perfect")
    axes.plot(predicted, observed, linewidth=1, color="tab:blue", label=name)
    axes.scatter(
        predicted,
        observed,
        s=20 + 180 * (populated / populated.max()),
        color="tab:blue",
        zorder=3,
        label="marker area ∝ rows in bin",
    )

    # The alerting region, marked rather than left to be inferred. A reader
    # checking whether the threshold is defensible is looking at what happens to
    # the right of this line.
    threshold = float(np.quantile(probability, 0.99))
    axes.axvline(
        threshold,
        linestyle=":",
        linewidth=1,
        color="firebrick",
        label=f"top 1% of scores (≥ {threshold:.3f})",
    )

    axes.set_xlabel("Predicted probability of a planted shape")
    axes.set_ylabel("Observed frequency in that bin")
    axes.set_title("Reliability, 10 uniform bins (synthetic data)")
    axes.set_xlim(-0.02, 1.02)
    axes.set_ylim(-0.02, 1.02)
    axes.legend(loc="upper left", fontsize="small")
    figure.tight_layout()

    path = directory / "reliability.png"
    figure.savefig(path, dpi=120)
    plt.close(figure)
    return path
