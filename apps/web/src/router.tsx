import { createRouter } from "@tanstack/react-router";
import { routeTree } from "./routeTree.gen";

// Application data access is RTK Query (see src/api/sentinelApi.ts). The router
// carries no data-fetching context of its own.
export const getRouter = () =>
  createRouter({
    routeTree,
    scrollRestoration: true,
    defaultPreloadStaleTime: 0,
  });
