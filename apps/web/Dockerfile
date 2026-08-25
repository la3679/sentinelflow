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
RUN bun run build

# ---------------------------------------------------------------------------
# Runtime
# ---------------------------------------------------------------------------
FROM nginxinc/nginx-unprivileged:1.31.3-alpine3.24 AS runtime

# The unprivileged image already runs as uid 101 and listens on 8080 rather
# than 80, so no capability is needed and no user is added here.

COPY --chown=101:101 infra/docker/web-nginx.conf /etc/nginx/conf.d/default.conf

# Only the client bundle. dist/server/ is a build artifact of the prerender
# step and is not served.
COPY --from=build --chown=101:101 /build/apps/web/dist/client/ /usr/share/nginx/html/

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 \
    CMD wget --quiet --spider http://127.0.0.1:8080/healthz || exit 1

# nginx's own entrypoint handles signals and template rendering.
