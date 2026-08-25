import { configureStore } from "@reduxjs/toolkit";
import { setupListeners } from "@reduxjs/toolkit/query";
import { useDispatch, useSelector } from "react-redux";

import { sentinelApi } from "@/api/sentinelApi";
import { sessionSlice } from "./sessionSlice";

export { mockSignIn, setRole, signOut, hydrateSession } from "./sessionSlice";
export type { SessionState } from "./sessionSlice";

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
export const useSession = () => useAppSelector((state) => state.session);
