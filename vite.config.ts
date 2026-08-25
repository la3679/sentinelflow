// @lovable.dev/vite-tanstack-config already includes the following — do NOT add them manually
// or the app will break with duplicate plugins:
//   - TanStack devtools (dev-only, first), tanstackStart, viteReact, tailwindcss, tsConfigPaths,
//     nitro (build-only using cloudflare as a default target), VITE_* env injection, @ path alias,
//     React/TanStack dedupe, error logger plugins, and sandbox detection (port/host/strictPort).
// You can pass additional config via defineConfig({ vite: { ... }, etc... }) if needed.
import { defineConfig } from "@lovable.dev/vite-tanstack-config";

// SentinelFlow ships as a client-rendered single-page application.
//
// See docs/adr/0009-frontend-component-library.md. The console's application
// backend is Spring Boot; running a second server runtime in front of it would
// add a server-side attack surface, a second deployment artifact, and a
// component the threat model would have to cover, for no benefit to an
// authenticated internal operations console.
//
// SPA mode prerenders a single static shell at build time and hydrates every
// route on the client, so the build output is static assets that the web
// container serves directly.
export default defineConfig({
  tanstackStart: {
    // Redirect TanStack Start's bundled server entry to src/server.ts (our SSR error wrapper).
    server: { entry: "server" },
    spa: {
      enabled: true,
      prerender: {
        // Prerender only the shell; every route renders on the client.
        crawlLinks: false,
      },
    },
  },
  // No Nitro server bundle: the deployable artifact is static files only.
  nitro: false,
});
