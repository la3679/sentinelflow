import { fetchBaseQuery, type BaseQueryFn } from "@reduxjs/toolkit/query";

import { API_BASE_URL } from "@/api/config";
import { sessionExpired } from "@/store/sessionSlice";

/**
 * One request, against `contracts/openapi/sentinelflow-api.yaml`.
 *
 * Every endpoint declares a real path, verb and body, and every one of them goes
 * over HTTP. The `transport: "mock"` marker that stood beside this through the
 * migration is gone with the last fixture it described.
 */
export interface SentinelRequest {
  /** Path below the API base, starting with a slash. */
  url: string;
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  params?: Record<string, string | number | boolean | undefined>;
  body?: unknown;
  /**
   * How to read a body that is not JSON.
   *
   * One endpoint answers `text/csv`: the alert export, which is a file somebody
   * opens in a spreadsheet rather than a cursor a client pages. It goes through
   * this transport like everything else so it carries the bearer token, so a
   * `401` ends the session in the one place that does that, and so a refusal
   * arrives as the same {@link SentinelError} every screen already handles — a
   * bare `fetch` beside the store would have none of that.
   */
  responseHandler?: (response: Response) => Promise<unknown>;
}

/**
 * A failure, in the one shape the console handles.
 *
 * The API answers every error as RFC 9457 (`application/problem+json`), so this
 * is that document plus the two cases a browser produces on its own: a request
 * that never got an answer, and a body that did not parse.
 */
export interface SentinelError {
  /** The HTTP status, or 0 when the request never reached the API. */
  status: number;
  /** RFC 9457 `title`, or a description of what went wrong instead. */
  title: string;
  /** RFC 9457 `detail`, when there is one. Safe to show; the API never puts internals here. */
  detail?: string;
  /** Ties this failure to its log line and span, when the response carried one. */
  correlationId?: string;
  /**
   * Everything else the problem body carried — `errors` on a validation
   * failure, and the `legalTargets` and `currentVersion` that make a 409 on an
   * alert answerable rather than merely reportable.
   */
  extensions?: Record<string, unknown>;
}

/** Reads the bearer token without importing the store, which would import this back. */
interface SessionShaped {
  session: { token: string | null; tokenType: string | null };
}

function bearerFrom(state: unknown): string | null {
  const session = (state as SessionShaped | undefined)?.session;
  if (!session?.token) return null;
  return `${session.tokenType ?? "Bearer"} ${session.token}`;
}

const http = fetchBaseQuery({
  baseUrl: API_BASE_URL,
  prepareHeaders: (headers, { getState }) => {
    const authorization = bearerFrom(getState());
    // Absent rather than empty when there is no session. A request that cannot
    // be authenticated should look anonymous to the API, not malformed.
    if (authorization) headers.set("Authorization", authorization);
    return headers;
  },
});

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function stringOr(value: unknown, fallback: string): string {
  return typeof value === "string" && value.length > 0 ? value : fallback;
}

/**
 * Turns whatever `fetchBaseQuery` reports into a {@link SentinelError}.
 *
 * The three cases are genuinely different and a screen should be able to tell
 * them apart: the API refused and said why; the API answered with something
 * that is not a problem document; or nothing answered at all, which on this
 * console most often means the API is not running or the origin is not on its
 * CORS allow-list (ADR-0013) — a distinction the browser deliberately does not
 * make visible to script, so the message says both.
 */
function toSentinelError(error: unknown): SentinelError {
  if (!isRecord(error)) {
    return { status: 0, title: "The request could not be completed." };
  }

  const status = error["status"];
  if (typeof status === "number") {
    const body = error["data"];
    if (isRecord(body)) {
      const { type, title, status: _status, detail, instance, correlationId, ...rest } = body;
      void type;
      void _status;
      void instance;
      const mapped: SentinelError = {
        status,
        title: stringOr(title, `The API answered ${status}.`),
      };
      if (typeof detail === "string") mapped.detail = detail;
      if (typeof correlationId === "string") mapped.correlationId = correlationId;
      if (Object.keys(rest).length > 0) mapped.extensions = rest;
      return mapped;
    }
    return { status, title: `The API answered ${status}.` };
  }

  if (status === "PARSING_ERROR") {
    return {
      status: typeof error["originalStatus"] === "number" ? (error["originalStatus"] as number) : 0,
      title: "The API's response could not be read.",
      detail: "The response was not the JSON this console expects.",
    };
  }

  return {
    status: 0,
    title: "The API could not be reached.",
    detail:
      "Nothing answered. Check that the API is running, and that this console's origin is in its allowed origins.",
  };
}

/** The one request made without a token. A 401 from it is a wrong password. */
const LOGIN_URL = "/auth/login";

/**
 * The console's single base query: HTTP, unless the endpoint says otherwise.
 *
 * <h2>A 401 to a request that carried a token ends the session, here</h2>
 *
 * There is no refresh token by design (ADR-0012 §3), so the expiry arrives
 * mid-session as a 401 on whatever the operator did next. Dropping the
 * credential in one place means no screen can be left holding a token it has
 * already been told is dead, and every screen learns about it the same way —
 * rather than each one deciding for itself what a 401 means.
 *
 * The two exclusions are deliberate. A 401 from `POST /auth/login` is a wrong
 * password, not an ended session; and a 401 to a request that carried no token
 * is a screen that should not have asked, which must not turn "never signed in"
 * into "your session ended".
 *
 */
export const sentinelBaseQuery: BaseQueryFn<SentinelRequest, unknown, SentinelError> = async (
  request,
  api,
  extraOptions,
) => {
  const { url, method, params, body, responseHandler } = request;
  const result = await http(
    {
      url,
      method: method ?? "GET",
      ...(params ? { params } : {}),
      ...(body === undefined ? {} : { body }),
      ...(responseHandler ? { responseHandler } : {}),
    },
    api,
    extraOptions,
  );

  if (result.error) {
    const mapped = toSentinelError(result.error);
    if (mapped.status === 401 && url !== LOGIN_URL && bearerFrom(api.getState()) !== null) {
      api.dispatch(sessionExpired());
    }
    return { error: mapped };
  }
  return { data: result.data };
};

/** Exported for its own test: the mapping is where a failure becomes readable or not. */
export const __testing = { toSentinelError };
