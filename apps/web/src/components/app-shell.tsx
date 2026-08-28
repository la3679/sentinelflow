import { Link, useLocation, useNavigate } from "@tanstack/react-router";
import { FlaskConical, LogOut } from "lucide-react";
import { useEffect, useState, type ReactNode } from "react";

import { Button } from "@/components/ui/button";
import { ROLE_LABELS } from "@/domain/labels";
import type { Role } from "@/domain/types";
import { principalRole, signedOut, useAppDispatch, useSession } from "@/store";
import { USING_MOCK_DATA } from "@/api/config";

const NAV_ITEMS = [
  { to: "/", label: "Operations overview" },
  { to: "/transactions/live", label: "Live transactions" },
  { to: "/alerts", label: "Alert queue" },
  { to: "/reports", label: "Reports" },
  { to: "/model", label: "Model & policy" },
  { to: "/health", label: "System health" },
  { to: "/about", label: "About" },
] as const;

export function SyntheticDataBanner() {
  return (
    <p className="flex items-start gap-2 border-b border-border bg-surface px-4 py-2 text-xs text-muted-foreground">
      <FlaskConical aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
      <span>
        Educational portfolio demonstration. All transactions, accounts, alerts and scores are
        fictional synthetic data.{" "}
        {USING_MOCK_DATA
          ? "Some screens still read an in-memory fixture rather than the API."
          : null}{" "}
        Not affiliated with any bank, financial institution or employer.
      </span>
    </p>
  );
}

/**
 * Sends an operator without a session to sign in, and remembers where they were
 * going.
 *
 * This is not a security boundary and does not pretend to be one: it decides
 * what is worth rendering. Every screen behind it fetches from an API that
 * refuses an unauthenticated request on its own, which is where the actual
 * decision is made.
 */
function RequireSession({ children }: { children: ReactNode }) {
  const session = useSession();
  const navigate = useNavigate();
  const location = useLocation();
  // Captured once, on the way in. Reading the location again after the redirect
  // has started would find `/login` and send the operator back to the screen
  // they were just sent from, with `next=/login`.
  const [intended] = useState(() => location.pathname);

  useEffect(() => {
    if (session.status === "authenticated") return;
    void navigate({ to: "/login", search: { next: intended }, replace: true });
  }, [session.status, navigate, intended]);

  if (session.status === "authenticated") return <>{children}</>;

  return (
    <div className="flex min-h-screen items-center justify-center bg-background px-4">
      <p role="status" className="text-sm text-muted-foreground">
        {session.status === "expired"
          ? "Your session ended. Taking you to sign in…"
          : "Taking you to sign in…"}
      </p>
    </div>
  );
}

function Chrome({ children }: { children: ReactNode }) {
  const session = useSession();
  const dispatch = useAppDispatch();
  const role = principalRole(session.roles);

  return (
    <div className="min-h-screen bg-background">
      <a href="#main-content" className="skip-link">
        Skip to main content
      </a>
      <SyntheticDataBanner />
      <header className="border-b border-border bg-sidebar">
        <div className="flex flex-wrap items-center justify-between gap-3 px-4 py-3">
          <div className="flex items-baseline gap-3">
            <Link to="/" className="text-base font-semibold tracking-tight">
              SentinelFlow
            </Link>
            <span className="text-xs text-muted-foreground">
              Transaction risk &amp; fraud operations console
            </span>
          </div>
          {session.status === "authenticated" ? (
            <div className="flex items-center gap-3">
              {/* Read from the token's roles, not chosen. Choosing your own role
                  is the interface equivalent of naming your own actor. */}
              <span className="text-xs text-muted-foreground">
                <span className="tabular">{session.username}</span>
                {role ? ` — ${ROLE_LABELS[role]}` : " — no role"}
              </span>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => {
                  // No navigation here: dropping the session makes the gate
                  // above redirect, and two navigations racing would decide
                  // between them which screen the operator comes back to.
                  dispatch(signedOut());
                }}
              >
                <LogOut aria-hidden="true" className="size-4" />
                Sign out
              </Button>
            </div>
          ) : null}
        </div>
        <nav aria-label="Primary" className="overflow-x-auto px-2">
          <ul className="flex min-w-max items-center gap-1 pb-1">
            {NAV_ITEMS.map((item) => (
              <li key={item.to}>
                <Link
                  to={item.to}
                  activeOptions={{ exact: item.to === "/" }}
                  className="inline-flex items-center rounded-md px-3 py-2 text-sm text-muted-foreground transition-colors hover:bg-sidebar-accent hover:text-foreground data-[status=active]:bg-sidebar-accent data-[status=active]:text-foreground data-[status=active]:shadow-[inset_0_-2px_0_0_var(--color-primary)]"
                >
                  {item.label}
                </Link>
              </li>
            ))}
          </ul>
        </nav>
      </header>
      <main id="main-content" className="mx-auto w-full max-w-[110rem] px-4 py-6">
        {children}
      </main>
      <footer className="border-t border-border px-4 py-6 text-xs text-muted-foreground">
        <p>
          SentinelFlow is an independent, educational, open-source portfolio project. It executes no
          real transactions and makes no real financial decisions.
        </p>
      </footer>
    </div>
  );
}

/**
 * The console's frame.
 *
 * `requireSession` is false only for screens whose whole point is to be
 * readable by somebody who has not signed in — the disclosure of what this
 * project is and is not.
 */
export function AppShell({
  children,
  requireSession = true,
}: {
  children: ReactNode;
  requireSession?: boolean;
}) {
  if (!requireSession) return <Chrome>{children}</Chrome>;
  return (
    <RequireSession>
      <Chrome>{children}</Chrome>
    </RequireSession>
  );
}

/** UX-only affordance: auditors see mutating controls disabled with an explanation. */
export function ReadOnlyNotice({ role }: { role: Role | null }) {
  if (role !== "AUDITOR") return null;
  return (
    <p className="rounded-md border border-border bg-surface px-3 py-2 text-xs text-muted-foreground">
      The Auditor role is read-only in this demonstration, so case actions are disabled. This is a
      user-experience hint only — real authorization is enforced by the backend services.
    </p>
  );
}
