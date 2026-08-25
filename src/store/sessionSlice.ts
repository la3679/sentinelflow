import { createSlice, type PayloadAction } from "@reduxjs/toolkit";

import { ROLES, type Role } from "@/domain/types";

export interface SessionState {
  /** Mock-only session marker. No token, no credential, no real authentication. */
  signedIn: boolean;
  operatorId: string;
  role: Role;
  /** True once the browser-side stored demo session has been read. */
  hydrated: boolean;
}

const initialState: SessionState = {
  signedIn: false,
  operatorId: "analyst.a1",
  role: "ANALYST",
  hydrated: false,
};

export const sessionSlice = createSlice({
  name: "session",
  initialState,
  reducers: {
    mockSignIn(state, action: PayloadAction<{ operatorId: string; role: Role }>) {
      state.signedIn = true;
      state.operatorId = action.payload.operatorId;
      state.role = action.payload.role;
      state.hydrated = true;
    },
    setRole(state, action: PayloadAction<Role>) {
      state.role = action.payload;
    },
    signOut(state) {
      state.signedIn = false;
      state.hydrated = true;
    },
    hydrateSession(state, action: PayloadAction<{ operatorId: string; role: Role } | null>) {
      state.hydrated = true;
      if (action.payload) {
        state.signedIn = true;
        state.operatorId = action.payload.operatorId;
        state.role = action.payload.role;
      }
    },
  },
});

export const { mockSignIn, setRole, signOut, hydrateSession } = sessionSlice.actions;

export function isRole(value: unknown): value is Role {
  return typeof value === "string" && (ROLES as readonly string[]).includes(value);
}
