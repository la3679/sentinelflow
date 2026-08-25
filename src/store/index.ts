import { configureStore } from "@reduxjs/toolkit";
import { setupListeners } from "@reduxjs/toolkit/query";
import { useDispatch, useSelector } from "react-redux";

import { sentinelApi } from "@/api/sentinelApi";
import { demoOperatorSlice } from "./demoOperatorSlice";

export {
  setDemoOperator,
  setRole,
  resetDemoOperator,
  DEFAULT_DEMO_OPERATOR,
} from "./demoOperatorSlice";
export type { DemoOperatorState } from "./demoOperatorSlice";

export function makeStore() {
  const store = configureStore({
    reducer: {
      [sentinelApi.reducerPath]: sentinelApi.reducer,
      demoOperator: demoOperatorSlice.reducer,
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

/** The fictional operator the prototype is being viewed as. Not an auth session. */
export const useDemoOperator = () => useAppSelector((state) => state.demoOperator);
