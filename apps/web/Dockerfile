# syntax=docker/dockerfile:1.19
#
# SentinelFlow operations console container image.
#
# The build context is the repository root, not apps/web: the Bun workspace
# root owns the single lockfile, and a frozen install needs it.
#
# The console is client-rendered (ADR-0009), so the artifact is static files.
# There is no Node or Bun runtime in the final image and no server-side
# rendering process to secure - Spring Boot stays the only application backend.

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------
FROM oven/bun:1.4.0-alpine AS build

WORKDIR /build

# Manifests before sources, so a component change does not re-resolve the tree.
COPY package.json bun.lock bunfig.toml ./
COPY apps/web/package.json apps/web/
RUN bun install --frozen-lockfile

COPY apps/web/ apps/web/
WORKDIR /build/apps/web

# Where the built console will send its requests. Vite inlines VITE_* at build
# time, so this is a build argument and not a runtime one: the value ends up in
# the bundle the browser downloads, and changing it means rebuilding this image.
#
# The default is the host's published API port, because the request is made by a
# browser on the host - http://api:8080 is a name only another container can
# resolve. The API must list this image's own origin in
# SENTINELFLOW_CORS_ALLOWED_ORIGINS (ADR-0013).
ARG VITE_API_BASE_URL=http://localhost:8080/api/v1
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}

RUN bun run build

# ---------------------------------------------------------------------------
# Runtime
# ---------------------------------------------------------------------------
FROM nginxinc/nginx-unprivileged:1.30.4-alpine3.24 AS runtime

# The unprivileged image already runs as uid 101 and listens on 8080 rather
# than 80, so no capability is needed and no user is added here.

# CVE-2026-14456: unbounded memory growth in OpenSSL's QUIC server path.
# Alpine 3.24 shipped the fix as 3.5.8-r0. This nginx tag still carries
# 3.5.7-r0, because it has not been rebuilt since the advisory. Taking the
# update from the repository is the fix; a .trivyignore would hide a
# vulnerability that is genuinely patched and genuinely available.
#
# Pinned rather than a blanket `apk upgrade`, so the image stays a function of
# this file rather than of the day it was built. root is needed because the
# unprivileged base has already dropped to 101, and it is handed straight back.
#
# REMOVE THIS BLOCK once the base image ships >= 3.5.8-r0. Check with
# `docker run --rm nginxinc/nginx-unprivileged:1.30.4-alpine3.24 apk list -I`.
USER root
RUN apk add --no-cache libcrypto3=3.5.8-r0 libssl3=3.5.8-r0
USER 101

COPY --chown=101:101 infra/docker/web-nginx.conf /etc/nginx/conf.d/default.conf

# Only the client bundle. dist/server/ is a build artifact of the prerender
# step and is not served.
COPY --from=build --chown=101:101 /build/apps/web/dist/client/ /usr/share/nginx/html/

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 \
    CMD wget --quiet --spider http://127.0.0.1:8080/healthz || exit 1

# nginx's own entrypoint handles signals and template rendering.
