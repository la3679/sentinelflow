/**
 * Where the console sends its requests.
 *
 * The console and the API are separate origins (ADR-0002, ADR-0013), so this is
 * an absolute URL rather than a path: a relative `/api/v1` resolves against the
 * console's own server, which serves static files and would answer with the
 * application shell — a 200 the client would then try to parse as JSON.
 *
 * Vite inlines `VITE_*` at build time. The value is in the bundle the browser
 * downloads and is not read at runtime, so changing it means rebuilding.
 * Whatever it points at must list this console's origin in
 * `SENTINELFLOW_CORS_ALLOWED_ORIGINS`, or the browser refuses every response
 * before the console sees it.
 */
export const API_BASE_URL: string =
  import.meta.env["VITE_API_BASE_URL"] ?? "http://localhost:8080/api/v1";
