"""Runtime configuration.

Every setting is read from the environment with an explicit type. A value that
fails validation stops the process at startup rather than surfacing as a
confusing failure on the first request that depends on it.
"""

from __future__ import annotations

from pathlib import Path
from typing import Self

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Settings for the scoring service.

    Sourced from environment variables prefixed ``SENTINELFLOW_SCORING_``. No
    setting here is a secret; credential-bearing settings are added alongside
    the dependency that needs them, and none carries a default.
    """

    model_config = SettingsConfigDict(
        env_prefix="SENTINELFLOW_SCORING_",
        env_file=None,
        extra="forbid",
        frozen=True,
    )

    host: str = Field(
        default="0.0.0.0",  # noqa: S104 - the service runs in a container and is
        # reached through the compose network; binding to loopback would make it
        # unreachable from the API container.
        description="Interface the HTTP server binds to.",
    )
    port: int = Field(default=8000, ge=1, le=65535, description="HTTP port.")
    log_level: str = Field(default="INFO", description="Root log level.")

    # Set by CI and by the container image build so a running instance can be
    # traced back to what produced it.
    git_sha: str = Field(default="unknown", description="Commit the build came from.")

    models_root: Path = Field(
        default=Path("models"),
        description=(
            "Registry root holding <model-name>/<model-version>/ entries. Relative to the "
            "working directory, which is apps/scoring for a local run and /app in the image."
        ),
    )

    # Pinning is the escape hatch for the one case discovery refuses to guess at:
    # two entries fitted on the running feature version, where picking either
    # would make which model is served a property of directory iteration order.
    # Both or neither — a name without a version names a directory of versions.
    model_name: str | None = Field(
        default=None,
        description=(
            "Pin the registry entry by name instead of discovering it. Needs model_version."
        ),
    )
    model_version: str | None = Field(
        default=None,
        description=(
            "Pin the registry entry by version instead of discovering it. Needs model_name."
        ),
    )

    @model_validator(mode="after")
    def _pin_is_complete(self) -> Self:
        if (self.model_name is None) != (self.model_version is None):
            raise ValueError(
                "SENTINELFLOW_SCORING_MODEL_NAME and SENTINELFLOW_SCORING_MODEL_VERSION are set "
                "together or not at all. Half a pin names a directory of versions, and choosing "
                "one of them is the guess this setting exists to avoid."
            )
        return self


def load_settings() -> Settings:
    """Build a settings instance from the current environment."""
    return Settings()
