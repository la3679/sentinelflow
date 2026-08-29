# ADR-0015 — Live updates: bounded polling now, Server-Sent Events when there is a stream to carry

- **Status:** Accepted
- **Date:** 2026-08-28
- **Related:** [ADR-0002](0002-monorepo-and-service-boundaries.md),
  [ADR-0006](0006-event-schema-and-versioning.md),
  [ADR-0012](0012-operator-authentication.md),
  [ADR-0013](0013-console-to-api-cross-origin-access.md),
  [ADR-0014](0014-where-the-console-s-remaining-screens-get-their-data.md)

## Context

The implementation plan lists "ADR-0015 SSE versus WebSockets" as a Phase 6 deliverable, and §8.6
of the build prompt says to use Server-Sent Events for the core dashboard unless research shows a
concrete need for WebSockets. That settles the smaller of the two questions and leaves the larger
one open: it chooses **between** the streaming transports without deciding whether this phase
should stream at all. Both halves are decided here, and they are decided now rather than at the
start of Phase 6 because the console did not talk to the API until the migration finished — an ADR
about live updates written against fixtures would have been about an imagined system.

Six facts about what is actually built, none of them assumptions:

1. **Exactly one screen refreshes on its own.** `apps/web/src/routes/transactions/live.tsx` passes
   `pollingInterval: 5000` to `useListTransactionsQuery`, and only while the operator is on the
   first page and has not pressed **Pause feed**. Every other screen refreshes when it is navigated
   to, or when a mutation the same browser made invalidates its tag.
2. **The alert queue has no refresh at all.** `listAlerts` provides `AlertList`, and the only things
   that invalidate it are the assignment, transition and note mutations — all of which are made by
   the browser that is already looking at the queue. **An alert raised by the pipeline, or a
   transition made by a second analyst, never appears** until the page is navigated away from and
   back.
3. **`setupListeners` is wired and does nothing.** `store/index.ts` calls it, which arms RTK Query's
   focus and reconnect events, but neither `createApi` nor any endpoint opts into `refetchOnFocus`
   or `refetchOnReconnect`. The wiring has never caused a request.
4. **Nothing produces transactions continuously.** `compose.yaml` runs PostgreSQL, Kafka, the topic
   creator, scoring, the API, the console, Prometheus and Grafana. There is no generator among them.
   Transactions arrive from `make seed` as a batch, from `make replay` four at a time, and from a
   hand-written `POST /transactions`. Between those bursts there is no event stream to push, and a
   stream that idles for minutes and then delivers a batch is a worse fit for a socket than for a
   poll.
5. **Authentication is a bearer token in a header, with a 30-minute default expiry** and no refresh
   token (ADR-0012 §1 and §3). There is no session cookie, deliberately.
6. **The API consumes `transaction.created` in one consumer group,** `transaction-risk`, and the
   console and the API are separate origins (ADR-0013).

`contracts/openapi/` describes no streaming endpoint. Adding one is a contract change with
producers, consumers, tests and docs attached, which is the cost this decision is weighing.

## Decision

### 1. Phase 6 ships bounded polling, and the two screens that need refreshing get it

Polling is the mechanism for the phase. Two changes make it complete rather than accidental:

- **The alert queue polls every 30 seconds, on its first page only.** Slower than the transaction
  feed because an alert is worked over minutes and the queue's order is a work order: rows that
  re-sort under a cursor while somebody is choosing one are worse than rows that are half a minute
  old. Beyond the first page it stops, for the reason the feed stops — a later page is somebody
  reading, not somebody watching.
- **`refetchOnFocus` and `refetchOnReconnect` are turned on for the whole API.** They make the
  `setupListeners` call that has always been there do the thing it was added for: a console left
  open on a second monitor is re-read when it is looked at again, and a laptop back from a dropped
  network re-reads instead of showing what it held before the drop. This is the cheapest correct
  answer to fact 2 above, and it covers every screen rather than the one that got an interval.

The transaction feed keeps its five seconds and its pause control, unchanged.

**A stream is not built in this phase.** Fact 4 is why: there is nothing to stream between the
bursts, and building the transport before the traffic would mean choosing its fan-out, its
back-pressure and its reconnection semantics against a load nobody has produced.

### 2. When a stream is built it is Server-Sent Events, and not WebSockets

**The traffic is one-directional.** Every operator action is already a mutation with an endpoint, a
status code, an RFC 9457 problem document, an optimistic-locking version and an audit row
(ADR-0012, and the Phase 5 alert workflow). A duplex transport would offer a second way to make
those same changes carrying none of that, and the first thing built on it would be the thing that
diverges from the audited path.

**SSE stays inside decisions already made.** It is an HTTP GET: ADR-0013's CORS configuration
applies to it unchanged, the bearer header goes on it unchanged, a `401` means what it means
everywhere else, and every proxy and every piece of the Phase 7 observability work sees a request
it already understands. A WebSocket upgrade leaves that model entirely — the handshake is not
subject to CORS, so the origin check has to be written by hand and tested by hand, and the frames
are invisible to HTTP-level metrics and tracing.

**Reconnection is in the protocol.** `Last-Event-ID` and a specified retry are part of SSE; the
equivalent over a socket is application code somebody has to write, get right, and keep right.
§8.6's requirement for reconnection and last-event handling is met by the transport rather than by
a library.

**Rejected: WebSockets.** The concrete need the build prompt asks for does not exist. Nothing the
console does needs a client-to-server frame, and the two properties a socket would buy — lower
per-message overhead and bidirectionality — are worth nothing at the message rate of fact 4.

### 3. The stream will be read with `fetch`, not with `EventSource`

`EventSource` cannot set request headers, and this API is authenticated by an `Authorization`
header. The two ways round that are both refused:

- **Rejected: the token in the query string.** It would be written into access logs, proxy logs and
  `Referer` headers. [`CLAUDE.md`](../../CLAUDE.md) forbids secrets in logs, and a credential in a
  URL is a credential in every log that URL touches.
- **Rejected: a session cookie for the stream alone.** ADR-0012 §1 rejected cookies for this console
  and named the CSRF surface they drag across origins. Reintroducing one for a single endpoint would
  mean the system has two authentication schemes, one of which is used once.

So the stream is a `fetch` carrying the bearer header, reading `text/event-stream` off the response
body. It costs the browser's automatic reconnect, which becomes explicit client code — an accepted
cost, because it is written once and it keeps every request on one authentication scheme and one
error path.

**A stream must not outlive the token that authorized it.** The server closes it at the token's
expiry and the client reopens with a live one, or the session ends the way it ends everywhere else.
Without that rule a stream opened at minute 29 of a 30-minute token is an authenticated channel with
no valid credential behind it, and the `401` that ends the session in `transport.ts` never fires
because no further request is ever made.

### 4. Three things must be true before the stream is worth building

Recorded so that a later session can tell "not yet" from "forgotten":

1. **Something produces continuously.** A generator, a scheduled replay, or a demo that posts while
   somebody watches. Until then the feed's refresh has nothing to beat.
2. **A fan-out that survives a second API instance.** An in-memory emitter registry fed by the Kafka
   consumer is correct for exactly one instance. With two, `transaction-risk` splits its partitions
   between them, and a browser attached to one instance silently never sees the events the other
   consumed — a defect that shows up as missing rows rather than as an error, which is the worst
   shape a defect can take. Fixing it means a real broadcast (per-instance group ids, or a shared
   bus), and that is a design with an ADR of its own.
3. **Phase 7's metric set.** The request volume polling actually generates should be measured before
   it is replaced, so that the replacement rests on a number rather than on the word "live".

### 5. Polling stays bounded, and the bound is visible

- The interval is a named constant beside the query it drives, not a number inside a hook call.
- Every polled endpoint is a paged endpoint whose size the server caps at 200 and rejects above,
  rather than clamping.
- Nothing polls a report, an aggregate, or the CSV export. Those are read when they are asked for.
- A feed nobody is watching does not poll: beyond the first page, and while paused, no interval is
  passed at all.

**The arithmetic cost, stated as arithmetic rather than as a measurement:** a feed left running
makes 720 requests an hour and the alert queue 120, which is 3600 divided by each interval. Nothing
has measured what those cost. It is acceptable for the single operator this demo has, it is not a
claim about anything larger, and Phase 8's rate limits are where it stops being free.

## Consequences

- **No contract change in Phase 6.** `contracts/openapi/` gains a streaming endpoint when §4's three
  preconditions hold, and this ADR is superseded at that point rather than quietly extended.
- **`setupListeners` becomes load-bearing**, and the console re-reads on focus and on reconnect
  everywhere. The screens composed from more than one request — the overview, from
  `GET /reports/alert-summary` and `GET /alerts` (ADR-0014 §3) — re-read both.
- **The alert queue stops being the one screen that could show an operator a queue that had moved on
  without them.** The gap fact 2 describes closes.
- Two more intervals exist, and both are subject to §5's rules. A third would be a reason to
  reconsider §1 rather than to add one.
- The phase's live-update deliverable is met by polling with the reconnection and fallback behaviour
  §8.6 asks for, and the streaming half of §8.6 becomes a decision with preconditions rather than an
  unbuilt promise.

## Revisit if

- **A continuous producer lands.** That is precondition 1, and the one most likely to arrive first —
  a demo generator would satisfy it on its own.
- **The API runs as more than one instance.** Precondition 2 stops being hypothetical, and the
  fan-out decision has to be made before a stream rather than after it.
- **Phase 7 measures the polling cost as material** against the rest of the API's traffic.
- **Anything needs a client-to-server frame.** That supersedes §2 rather than extending it, and it
  should be argued against the audited mutation endpoints it would sit beside.
