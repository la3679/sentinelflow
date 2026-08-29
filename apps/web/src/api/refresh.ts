/**
 * When a screen re-asks the API on an interval, and when it stops.
 *
 * ADR-0015 decides that Phase 6's live updates are bounded polling rather than
 * a stream, and §5 of it puts three rules on every interval: the number is a
 * named constant beside the query it drives, the endpoint underneath is paged
 * with a server-enforced maximum, and **a feed nobody is watching does not
 * poll**.
 *
 * The last of those is the one that is easy to get subtly wrong in each screen
 * separately, so it lives here rather than as a condition copied into every
 * route.
 */

/**
 * Subscription options for a query that refreshes only while `watching` is true.
 *
 * Returns an object with **no `pollingInterval` key at all** when it is false,
 * rather than a zero or an undefined. RTK Query merges subscription options
 * across every component subscribed to the same cache entry, and a key that is
 * present is a key that participates in that merge — so "not polling" has to be
 * an absence to stay an absence.
 *
 * @param watching whether somebody is watching this screen right now: on its
 *     first page, and not paused.
 * @param intervalMs how often to re-ask while they are.
 */
export function refreshWhile(watching: boolean, intervalMs: number): { pollingInterval?: number } {
  return watching ? { pollingInterval: intervalMs } : {};
}
