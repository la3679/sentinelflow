import { createSlice, type PayloadAction } from "@reduxjs/toolkit";

import { ROLES, type Role } from "@/domain/types";

/**
 * Which fictional operator the prototype is currently being viewed as.
 *
 * This is NOT authentication and deliberately does not model one. There is no
 * signed-in flag, no credential, no token, no cookie, and nothing is persisted
 * to localStorage or sessionStorage — reloading the page resets it to the
 * default operator. Every route is reachable directly.
 *
 * `role` changes which controls the interface offers. It is a presentation
 * concern only. Real authentication and authorization are enforced by the
 * SentinelFlow API and are out of scope for this phase.
 */
export interface DemoOperatorState {
  operatorId: string;
  role: Role;
}

export const DEFAULT_DEMO_OPERATOR: DemoOperatorState = {
  operatorId: "analyst.a1",
  role: "ANALYST",
};

const initialState: DemoOperatorState = { ...DEFAULT_DEMO_OPERATOR };

export const demoOperatorSlice = createSlice({
  name: "demoOperator",
  initialState,
  reducers: {
    setDemoOperator(state, action: PayloadAction<DemoOperatorState>) {
      state.operatorId = action.payload.operatorId;
      state.role = action.payload.role;
    },
    setRole(state, action: PayloadAction<Role>) {
      state.role = action.payload;
    },
    resetDemoOperator() {
      return { ...DEFAULT_DEMO_OPERATOR };
    },
  },
});

export const { setDemoOperator, setRole, resetDemoOperator } = demoOperatorSlice.actions;

export function isRole(value: unknown): value is Role {
  return typeof value === "string" && (ROLES as readonly string[]).includes(value);
}
