import { configureStore } from "@reduxjs/toolkit";
import { setupListeners } from "@reduxjs/toolkit/query";
import { useDispatch, useSelector } from "react-redux";

import { sentinelApi } from "@/api/sentinelApi";
import { canMutate, type Role } from "@/domain/types";
import { sessionSlice } from "./sessionSlice";

export { signedIn, signedOut, sessionExpired, isRole, knownRoles } from "./sessionSlice";
export type { SessionState, SessionStatus, SignedInPayload } from "./sessionSlice";

/**
 * A store, and nothing global.
 *
 * `setupListeners` deliberately is **not** called here. It adds `focus` and
 * `visibilitychange` listeners to the window, and now that the API opts into
 * `refetchOnFocus` (ADR-0015 §1) those listeners issue real requests. A factory
 * that registered them would mean every store a test built kept firing requests
 * through a `fetch` that test had already restored.
 *
 * The application registers them once, below, and that is the only
 * registration there is: `setupListeners` guards itself with a module-level
 * flag and refuses a second one, so a test cannot arm its own store and must
 * exercise this one.
 */
export function makeStore() {
  return configureStore({
    reducer: {
      [sentinelApi.reducerPath]: sentinelApi.reducer,
      session: sessionSlice.reducer,
    },
    middleware: (getDefaultMiddleware) => getDefaultMiddleware().concat(sentinelApi.middleware),
  });
}

export const store = makeStore();

// Arms the refetch-on-focus and refetch-on-reconnect behaviour ADR-0015 §1
// decides, once, for the application's own store. Its teardown is discarded
// because this store lives as long as the document does. `setupListeners`
// checks for `window` itself, so this is inert during server rendering.
setupListeners(store.dispatch);

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
