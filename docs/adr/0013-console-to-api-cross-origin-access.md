# ADR-0013 — How the browser is allowed to call the API from another origin

- **Status:** Accepted
- **Date:** 2026-08-28
- **Related:** [ADR-0002](0002-monorepo-and-service-boundaries.md),
  [ADR-0009](0009-frontend-component-library.md),
  [ADR-0012](0012-operator-authentication.md)

## Context

Phase 6 replaces the console's mock transport with real HTTP. The moment it does, a browser is
making the request, and a browser will not let a page at one origin read a response from another
unless the second origin says so.

The two origins are already fixed by decisions that are not being reopened here. ADR-0002 put the
console and the API in separate deployment units; `compose.yaml` publishes the console on
`localhost:5173` and the API on `localhost:8080`; and ADR-0012 §1 rejected cookie sessions partly
**because** "the console is deliberately a separate origin from the API". Every one of those is a
recorded position, and the console has simply never made a real request before, so nothing has ever
had to answer the question.

Today `src/api/config.ts` defaults `API_BASE_URL` to the relative `/api/v1`. That is a Lovable-era
default from a build that never left its own mock layer: against the console's own nginx it
resolves to a path that container does not serve, and the console would get its own SPA shell back
with a 200 and try to parse HTML as JSON. It is not a decision, it is an untested default.

There is a third party to consider and it is the one that makes this an ADR rather than a
configuration line: **the API is the system that holds the audit trail**, and CORS is the control
that decides which web origins a browser will let read it.

## Decision

### 1. The origins stay separate, and the API states which ones may read it

The console calls the API at an absolute base URL — `VITE_API_BASE_URL`, defaulting to
`http://localhost:8080/api/v1` for the compose stack — and the API answers CORS preflights for an
**explicit allow-list** read from `SENTINELFLOW_CORS_ALLOWED_ORIGINS`.

**Rejected: proxy `/api/v1` through the console's nginx and the Vite dev server.** It works, it
needs no CORS at all, and it is what many deployments do. It was rejected because it makes the two
services one origin as far as every browser is concerned, which quietly removes the premise
ADR-0012 §1 leans on when it rejects cookie sessions, and because it moves API routing into a
container whose job is to serve static files — a change to the API's address would then be a change
to the frontend's deployment. Keeping them separate keeps the boundary this project exists to show
visible rather than hidden behind a rewrite rule.

**Rejected: `allowedOrigins: ["*"]`.** A wildcard is not a permission for a demo, it is the absence
of one, and it would be the single line of this repository most likely to be copied into something
that mattered. It is also incompatible with credentials by specification, so it would break the
moment anything here used them.

**Rejected: `allowedOriginPatterns` with a `localhost` pattern.** It reads as convenient and it
matches any port on the loopback interface, which includes whatever else the developer happens to be
running. An allow-list of two entries is not the thing that was hard about this.

### 2. The dev server moves off port 8080

`@lovable.dev/vite-tanstack-config` defaults `bun run dev` to port 8080, which is the port
`compose.yaml` publishes the **API** on. Nothing has collided so far because the console has never
made a request; the moment it does, `make up && bun run dev` is a developer whose console and whose
API are fighting over one port, and Vite would quietly take another and change the origin the API
was told to expect. The dev server is pinned to **5174** instead, one above the compose console.

### 3. The list has a default, and the default is the demo

Unlike `SENTINELFLOW_JWT_SECRET`, this property **does** default —
`http://localhost:5173,http://localhost:5174`: the compose console and the Vite dev server. The
distinction is that a default secret is a credential everybody shares, while a default origin list
grants nothing to anybody who cannot already reach the developer's own loopback interface. A demo
that cannot be started without setting an environment variable nobody can guess is a demo nobody
runs.

Every entry is validated at startup to be an absolute `http` or `https` origin with no path, and
`*` is refused explicitly with a message saying why. A malformed entry that Spring silently ignored
would present as "the console cannot reach the API" and send somebody looking at the network.

### 4. CORS is not a security control, and this ADR does not pretend it is

It constrains what a **browser** will let a page read. It does nothing to `curl`, to a server-side
client, or to anything that ignores the response headers, and every one of those still meets
`anyRequest().authenticated()` and a signature check. What CORS actually buys here is that a page on
some other site cannot read this API's responses using a token it does not have, and — because
credentials are **not** allowed on these requests (§5) — cannot ride an ambient one either.

### 5. Credentials are not allowed on cross-origin requests

`allowCredentials` stays false. The console holds its token in memory and sends it as an explicit
`Authorization` header, which the allow-list already covers; nothing here authenticates from a
cookie, and ADR-0012 §1 says CSRF protection is off precisely on that condition. Turning credentials
on would permit the ambient-credential flow this design does not use and would make the disabled
CSRF filter wrong.

### 6. Preflights are cached, and the actuator is not exposed to the browser

`maxAge` is one hour, so a mutation is not preceded by an `OPTIONS` on every click. The CORS
configuration is registered for `/api/v1/**` only: `/actuator/**` is not a browser surface, and the
console reads none of it. When Phase 6 decides what a system-health screen may show, it will do so
through this API rather than by widening this rule — which is the shape the audit already predicted
for `GET /health`.

## Consequences

- The console's base URL is configuration, not a relative path, and `.env.example` and
  `compose.yaml` carry it. A deployment that changes either origin changes two values, and if it
  changes only one the failure is a browser console error naming the origin that was refused.
- A request from an origin not on the list is refused by the browser before the API sees it, and one
  from a non-browser client is unaffected. Nobody should read a CORS refusal as an authorization
  event.
- Phase 8 inherits two follow-ons already named elsewhere: moving the actuator to a management port
  that is not published, and giving ingestion its own credential. Neither is widened by this ADR.

**Revisit if:** the console and the API are ever served from one origin in a real deployment, in
which case this becomes a same-origin deployment with the allow-list empty rather than a rewrite of
the transport; or anything here starts authenticating from a cookie, at which point §5 and the
disabled CSRF filter both stop being correct and must change together.
