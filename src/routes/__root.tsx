import {
  Outlet,
  Link,
  createRootRoute,
  useRouter,
  HeadContent,
  Scripts,
} from "@tanstack/react-router";
import { Provider } from "react-redux";
import { useEffect, useState, type ReactNode } from "react";

import appCss from "../styles.css?url";
import { reportLovableError } from "../lib/lovable-error-reporting";
import { makeStore } from "@/store";
import { Button } from "@/components/ui/button";

function NotFoundComponent() {
  return (
    <div className="flex min-h-screen items-center justify-center bg-background px-4">
      <main className="max-w-md text-center">
        <p className="tabular text-sm text-muted-foreground">HTTP 404</p>
        <h1 className="mt-2 text-2xl font-semibold">Page not found</h1>
        <p className="mt-2 text-sm text-muted-foreground">
          The console route you requested does not exist. It may have been renamed or the record no
          longer exists in this synthetic dataset.
        </p>
        <div className="mt-6">
          <Link
            to="/"
            className="inline-flex items-center justify-center rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90"
          >
            Back to operations overview
          </Link>
        </div>
      </main>
    </div>
  );
}

function ErrorComponent({ error, reset }: { error: Error; reset: () => void }) {
  const router = useRouter();
  useEffect(() => {
    reportLovableError(error, { boundary: "tanstack_root_error_component" });
  }, [error]);

  return (
    <div className="flex min-h-screen items-center justify-center bg-background px-4">
      <main className="max-w-md text-center" role="alert">
        <h1 className="text-2xl font-semibold">This screen could not be rendered</h1>
        <p className="mt-2 text-sm text-muted-foreground">
          The console hit an unexpected error. Retrying re-runs the view; no data was changed.
        </p>
        <div className="mt-6 flex flex-wrap justify-center gap-2">
          <Button
            type="button"
            onClick={() => {
              void router.invalidate();
              reset();
            }}
          >
            Retry this screen
          </Button>
          <Button asChild variant="outline">
            <a href="/">Back to overview</a>
          </Button>
        </div>
      </main>
    </div>
  );
}

export const Route = createRootRoute({
  head: () => ({
    meta: [
      { charSet: "utf-8" },
      { name: "viewport", content: "width=device-width, initial-scale=1" },
      { title: "SentinelFlow — Fraud Operations Console" },
      {
        name: "description",
        content:
          "SentinelFlow is an independent educational transaction-risk and fraud-operations console built on synthetic data.",
      },
      { property: "og:title", content: "SentinelFlow — Fraud Operations Console" },
      {
        property: "og:description",
        content:
          "Independent educational fraud-operations console for reviewing synthetic transaction risk.",
      },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary_large_image" },
    ],
    links: [
      { rel: "stylesheet", href: appCss },
      { rel: "preconnect", href: "https://fonts.googleapis.com" },
      { rel: "preconnect", href: "https://fonts.gstatic.com", crossOrigin: "anonymous" },
      {
        rel: "stylesheet",
        href: "https://fonts.googleapis.com/css2?family=IBM+Plex+Sans:wght@400;500;600&family=IBM+Plex+Mono:wght@400;500&display=swap",
      },
      { rel: "icon", href: "/favicon.ico", type: "image/x-icon" },
    ],
  }),
  shellComponent: RootShell,
  component: RootComponent,
  notFoundComponent: NotFoundComponent,
  errorComponent: ErrorComponent,
});

function RootShell({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <head>
        <HeadContent />
      </head>
      <body>
        {children}
        <Scripts />
      </body>
    </html>
  );
}

function RootComponent() {
  const [store] = useState(() => makeStore());

  return (
    <Provider store={store}>
      {/* Required: nested routes render here. Removing <Outlet /> breaks all child routes. */}
      <Outlet />
    </Provider>
  );
}
