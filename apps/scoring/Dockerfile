# syntax=docker/dockerfile:1.19
#
# SentinelFlow scoring service container image.
#
# Two stages: uv, the lockfile resolution and the build backend exist only in
# the build stage. The runtime image carries the interpreter, the virtual
# environment and the application.

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------
FROM python:3.13.15-slim-trixie AS build

# uv is copied from its own published image rather than pip-installed, so the
# version is pinned by digest-addressable tag instead of by whatever pip
# resolves at build time.
COPY --from=ghcr.io/astral-sh/uv:0.12.2 /uv /uvx /usr/local/bin/

ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=never

WORKDIR /build

# Dependencies before source, so a code change does not re-resolve the lockfile.
# --frozen fails if uv.lock is out of date with pyproject.toml rather than
# quietly resolving something different from what CI tested.
COPY pyproject.toml uv.lock ./
RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync --frozen --no-install-project --no-dev

COPY src/ src/
COPY README.md ./
# --no-editable matters: uv installs the project in editable mode by default,
# which leaves a .pth file in the virtual environment pointing at the build
# stage's source path. That path does not exist in the runtime image, and the
# service dies at import time with "No module named sentinelflow_scoring".
RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync --frozen --no-dev --no-editable

# ---------------------------------------------------------------------------
# Runtime
# ---------------------------------------------------------------------------
FROM python:3.13.15-slim-trixie AS runtime

RUN groupadd --system --gid 10002 sentinelflow \
    && useradd --system --uid 10002 --gid sentinelflow --no-create-home --shell /usr/sbin/nologin sentinelflow

WORKDIR /app

# Only the virtual environment. The package is installed into it by the build
# stage, so the source tree is not copied a second time.
COPY --from=build --chown=10002:10002 /build/.venv /app/.venv

# Put the virtual environment first on PATH instead of activating it: there is
# no shell in the process chain to activate anything.
ENV PATH="/app/.venv/bin:${PATH}" \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

USER 10002:10002

EXPOSE 8000

# urllib rather than curl: the interpreter is already here, so the probe adds
# no packages to the image.
HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=5 \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/health/ready', timeout=2).status == 200 else 1)" || exit 1

# Exec form so uvicorn is pid 1 and receives SIGTERM directly.
ENTRYPOINT ["python", "-m", "sentinelflow_scoring"]
