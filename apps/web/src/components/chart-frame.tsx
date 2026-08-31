import { useSyncExternalStore, type ReactNode } from "react";

import { Skeleton } from "@/components/ui/skeleton";

/**
 * Nothing ever changes after hydration, so there is nothing to subscribe to.
 *
 * Hoisted to module scope rather than written inline: `useSyncExternalStore`
 * re-subscribes whenever the `subscribe` identity changes, and a function
 * literal in the component body is a new identity on every render.
 */
const neverChanges = () => () => {};

/**
 * Whether this render is happening in the browser, after hydration.
 *
 * `useSyncExternalStore` rather than a `useState` flag set from an effect. The
 * flag version renders once with `false`, sets state inside `useEffect`, and
 * renders again - a cascading render that `eslint-plugin-react-hooks` 7 reports
 * as an error, and that is a fair report rather than a false positive. This
 * hook has one snapshot on the server and another on the client, which is the
 * thing the API exists to express, and React swaps between them at hydration
 * without a second commit driven by our own state.
 */
function useHydrated(): boolean {
  return useSyncExternalStore(
    neverChanges,
    () => true,
    () => false,
  );
}

/**
 * Charts are decorative-with-a-table-alternative and browser-only: rendering is
 * deferred until after hydration so server output stays stable.
 */
export function ChartFrame({
  height = 240,
  label,
  children,
}: {
  height?: number;
  label: string;
  children: ReactNode;
}) {
  const hydrated = useHydrated();

  if (!hydrated) {
    return <Skeleton className="w-full bg-muted" style={{ height }} aria-hidden="true" />;
  }

  return (
    <figure className="m-0" style={{ height }} role="img" aria-label={label}>
      {children}
    </figure>
  );
}
