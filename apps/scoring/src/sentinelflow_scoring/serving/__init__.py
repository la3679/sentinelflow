"""The request path: loading a model, scoring with it, and answering the contract.

Separate from :mod:`sentinelflow_scoring.training`, which is offline. The split is
ADR-0010 §6's — training is a command, never an API side effect — and it is
visible here as a one-way dependency: serving imports the registry and the score
rescale, and nothing in training imports this package.
"""

from sentinelflow_scoring.serving.model import ActiveModel, ScoreOutcome, load_active

__all__ = ["ActiveModel", "ScoreOutcome", "load_active"]
