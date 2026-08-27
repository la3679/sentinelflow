"""Offline training, evaluation and the model registry.

Imported by the training command and by the model loader. **Not by the request
path**: `plots` pulls matplotlib, which is a training-only dependency and is not
installed in the serving image (R-2026-08-26-01), so importing this package there
would fail at startup rather than at the moment a plot was wanted.
"""

from sentinelflow_scoring.training.dataset import DatasetError, TrainingData, load
from sentinelflow_scoring.training.registry import ModelManifest, RegistryError

__all__ = [
    "DatasetError",
    "ModelManifest",
    "RegistryError",
    "TrainingData",
    "load",
]
