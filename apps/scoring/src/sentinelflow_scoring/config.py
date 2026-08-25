"""Runtime configuration.

Every setting is read from the environment with an explicit type. A value that
fails validation stops the process at startup rather than surfacing as a
confusing failure on the first request that depends on it.
"""

from __future__ import annotations

from pydantic import Field
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


def load_settings() -> Settings:
    """Build a settings instance from the current environment."""
    return Settings()
