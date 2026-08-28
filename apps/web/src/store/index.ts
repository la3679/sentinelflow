import { configureStore } from "@reduxjs/toolkit";
import { setupListeners } from "@reduxjs/toolkit/query";
import { useDispatch, useSelector } from "react-redux";

import { sentinelApi } from "@/api/sentinelApi";
import { canMutate, type Role } from "@/domain/types";
import { sessionSlice } from "./sessionSlice";

export { signedIn, signedOut, sessionExpired, isRole, knownRoles } from "./sessionSlice";
export type { SessionState, SessionStatus, SignedInPayload } from "./sessionSlice";

export function makeStore() {
  const store = configureStore({
    reducer: {
      [sentinelApi.reducerPath]: sentinelApi.reducer,
      session: sessionSlice.reducer,
    },
    middleware: (getDefaultMiddleware) => getDefaultMiddleware().concat(sentinelApi.middleware),
  });
  setupListeners(store.dispatch);
  return store;
}

export const store = makeStore();

export type AppStore = typeof store;
export type RootState = ReturnType<AppStore["getState"]>;
export type AppDispatch = AppStore["dispatch"];

export const useAppDispatch = useDispatch.withTypes<AppDispatch>();
export const useAppSelector = useSelector.withTypes<RootState>();

/** The signed-in operator, or the reason there is not one. */
export const useSession = () => useAppSelector((state) => state.session);

/**
 * The most privileged role the operator holds, which is the one the interface
 * reads.
 *
 * The API makes the same choice for the same reason — `AuthenticatedOperator`
 * records an administrator who is also an analyst as an administrator, because
 * that is the authority the action rested on. Two different answers to "which
 * role" would mean the console offering a control under one capacity and the
 * audit trail recording it under another.
 */
const BY_AUTHORITY: readonly Role[] = ["ADMINISTRATOR", "ANALYST", "AUDITOR"];

export function principalRole(roles: readonly Role[]): Role | null {
  return BY_AUTHORITY.find((role) => roles.includes(role)) ?? null;
}

/** UX affordance only: whether to render case actions at all. The API decides. */
export function sessionCanMutate(roles: readonly Role[]): boolean {
  const role = principalRole(roles);
  return role !== null && canMutate(role);
}
