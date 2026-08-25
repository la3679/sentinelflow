/**
 * Base URL of the external SentinelFlow API. Read from the environment so the
 * mock layer can be swapped for the real Spring Boot gateway without code
 * changes elsewhere.
 */
export const API_BASE_URL: string = import.meta.env["VITE_API_BASE_URL"] ?? "/api/v1";

/** True while the console is served from deterministic mock fixtures. */
export const USING_MOCK_DATA = true;
