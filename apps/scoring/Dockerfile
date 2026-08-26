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

# Remove the interpreter's bundled installer. The application runs entirely
# from /app/.venv, so pip and setuptools are never used at runtime - but they
# are scanned, and the versions the base image ships carry fixable HIGH
# advisories (GHSA-6v7p-g79w-8964 in pip's vendored msgpack, CVE-2025-47273 in
# setuptools). Deleting an installer that a runtime image has no business
# containing removes the finding and the capability behind it at the same time.
RUN python -m pip uninstall --yes pip setuptools wheel >/dev/null 2>&1 || true;     rm -rf /usr/local/lib/python3.13/site-packages/pip     /usr/local/lib/python3.13/site-packages/pip-*     /usr/local/lib/python3.13/site-packages/setuptools     /usr/local/lib/python3.13/site-packages/setuptools-*     /usr/local/lib/python3.13/site-packages/pkg_resources     /usr/local/lib/python3.13/site-packages/wheel     /usr/local/lib/python3.13/site-packages/wheel-*     /usr/local/bin/pip /usr/local/bin/pip3 /usr/local/bin/pip3.13     /usr/local/bin/wheel

# CVE-2026-14456: unbounded memory growth in OpenSSL's QUIC server path.
# Debian shipped the fix as 3.5.7-1~deb13u2. The python:3.13.15-slim-trixie tag
# still carries 3.5.6-1~deb13u2, because it has not been rebuilt since the
# advisory. Taking the update from the archive is the fix; a .trivyignore would
# hide a vulnerability that is genuinely patched and genuinely available.
#
# Pinned rather than a blanket `apt-get upgrade`, so the image stays a function
# of this file rather than of the day it was built.
#
# REMOVE THIS BLOCK once the base image ships >= 3.5.7-1~deb13u2. Check with
# `docker run --rm python:3.13-slim-trixie dpkg-query -W libssl3t64`. If Debian
# rotates this exact version out of the archive first, the build fails here
# with a clear message and the fix is to delete the block.
RUN apt-get update \
    && apt-get install --yes --no-install-recommends \
        libssl3t64=3.5.7-1~deb13u2 \
        openssl=3.5.7-1~deb13u2 \
    && rm -rf /var/lib/apt/lists/*

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
