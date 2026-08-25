"""Run the service with uvicorn: ``python -m sentinelflow_scoring``."""

from __future__ import annotations

import uvicorn

from sentinelflow_scoring.config import load_settings


def main() -> None:
    settings = load_settings()
    uvicorn.run(
        "sentinelflow_scoring.app:app",
        host=settings.host,
        port=settings.port,
        log_level=settings.log_level.lower(),
    )


if __name__ == "__main__":
    main()
