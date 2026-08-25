import { Link, useNavigate } from "@tanstack/react-router";
import { FlaskConical, LogOut } from "lucide-react";
import { useEffect, type ReactNode } from "react";

import { Button } from "@/components/ui/button";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { ROLE_LABELS } from "@/domain/labels";
import { ROLES, type Role } from "@/domain/types";
import { setRole, signOut, useAppDispatch, useSession } from "@/store";
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
          ? "This build reads from an in-memory mock layer; no backend is connected."
          : null}{" "}
        Not affiliated with any bank, financial institution or employer.
      </span>
    </p>
  );
}

export function AppShell({ children }: { children: ReactNode }) {
  const session = useSession();
  const dispatch = useAppDispatch();
  const navigate = useNavigate();

  useEffect(() => {
    if (session.hydrated && !session.signedIn) {
      void navigate({ to: "/login" });
    }
  }, [session.hydrated, session.signedIn, navigate]);

  if (!session.signedIn) {
    return (
      <div className="min-h-screen bg-background">
        <SyntheticDataBanner />
        <main className="mx-auto max-w-md px-4 py-16" role="status" aria-live="polite">
          <p className="text-sm text-muted-foreground">Restoring your demonstration session…</p>
        </main>
      </div>
    );
  }

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
          <div className="flex items-center gap-3">
            <div className="flex items-center gap-2">
              <label htmlFor="role-select" className="text-xs text-muted-foreground">
                Simulated role
              </label>
              <Select
                value={session.role}
                onValueChange={(value) => dispatch(setRole(value as Role))}
              >
                <SelectTrigger id="role-select" className="w-56">
                  <SelectValue placeholder="Select a role" />
                </SelectTrigger>
                <SelectContent>
                  {ROLES.map((role) => (
                    <SelectItem key={role} value={role}>
                      {ROLE_LABELS[role]}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <span className="tabular hidden text-xs text-muted-foreground md:inline">
              {session.operatorId}
            </span>
            <Button type="button" variant="outline" size="sm" onClick={() => dispatch(signOut())}>
              <LogOut aria-hidden="true" className="size-4" />
              Sign out
            </Button>
          </div>
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

/** UX-only affordance: auditors see mutating controls disabled with an explanation. */
export function ReadOnlyNotice({ role }: { role: Role }) {
  if (role !== "AUDITOR") return null;
  return (
    <p className="rounded-md border border-border bg-surface px-3 py-2 text-xs text-muted-foreground">
      The Auditor role is read-only in this demonstration, so case actions are disabled. This is a
      user-experience hint only — real authorization is enforced by the backend services.
    </p>
  );
}
