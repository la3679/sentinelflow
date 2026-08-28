import { createSlice, type PayloadAction } from "@reduxjs/toolkit";

import { ROLES, type Role } from "@/domain/types";

/**
 * The operator's session: a real one, held in memory and nowhere else.
 *
 * <h2>Nothing here is persisted, and that is the design</h2>
 *
 * The token is a credential. `.claude/rules/frontend.md` forbids session or
 * authorization state in browser storage and a test enforces it, so a reload
 * signs the operator out. That is the honest consequence of ADR-0012 §3's
 * decision to take a short expiry instead of a refresh token: there is nothing
 * that could restore a session that a stolen `localStorage` entry could not
 * also restore.
 *
 * <h2>`expired` is a different state from `anonymous`</h2>
 *
 * A token that ran out mid-review and a browser that never had one need
 * different sentences on the sign-in screen — one of them lost work. Collapsing
 * them would leave an analyst who was typing a note being told nothing happened.
 *
 * <h2>`roles` decides what is offered, never what is allowed</h2>
 *
 * They come from `POST /auth/login`, which sends the roles held at login beside
 * the token rather than inside it. Authorization is the API's and is made from
 * the claim in the token; this list only decides which controls are worth
 * rendering. Disabling a button authorizes nothing.
 */
export type SessionStatus = "anonymous" | "authenticated" | "expired";

export interface SessionState {
  status: SessionStatus;
  /** The bearer credential. Never logged, never persisted, never put in a URL. */
  token: string | null;
  /** Sent by the API rather than assumed, so a client is not guessing "Bearer". */
  tokenType: string | null;
  /** ISO-8601. Beside the token so nothing here has to decode one. */
  expiresAt: string | null;
  /** What the operator typed. The API's `sub` is a UUID, which is not a thing to show a person. */
  username: string | null;
  roles: Role[];
}

export interface SignedInPayload {
  username: string;
  token: string;
  tokenType: string;
  expiresAt: string;
  roles: Role[];
}

const SIGNED_OUT: SessionState = {
  status: "anonymous",
  token: null,
  tokenType: null,
  expiresAt: null,
  username: null,
  roles: [],
};

export const sessionSlice = createSlice({
  name: "session",
  initialState: SIGNED_OUT,
  reducers: {
    signedIn(state, action: PayloadAction<SignedInPayload>) {
      state.status = "authenticated";
      state.token = action.payload.token;
      state.tokenType = action.payload.tokenType;
      state.expiresAt = action.payload.expiresAt;
      state.username = action.payload.username;
      state.roles = action.payload.roles;
    },
    /** Deliberate. The operator asked to leave. */
    signedOut() {
      return { ...SIGNED_OUT };
    },
    /**
     * The API answered 401 to a request that carried a token.
     *
     * The credential is dropped in the same reducer that records why, so there
     * is no state in which the console still holds a token it knows is dead.
     */
    sessionExpired() {
      return { ...SIGNED_OUT, status: "expired" as const };
    },
  },
});

export const { signedIn, signedOut, sessionExpired } = sessionSlice.actions;

export function isRole(value: unknown): value is Role {
  return typeof value === "string" && (ROLES as readonly string[]).includes(value);
}

/**
 * The roles from a login response, with anything unrecognised dropped.
 *
 * The API's contract lists three, and a fourth arriving means this console is
 * older than the API it is talking to. Dropping it offers fewer controls, which
 * is the safe direction; keeping it would put a value in the store that
 * `ROLE_LABELS` cannot render.
 */
export function knownRoles(value: unknown): Role[] {
  return Array.isArray(value) ? value.filter(isRole) : [];
}
