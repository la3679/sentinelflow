import { useEffect } from "react";

import { hydrateSession, isRole } from "./sessionSlice";
import { useAppDispatch, useSession } from "./index";

const STORAGE_KEY = "sentinelflow.demo-session";

/**
 * Restores and persists the demonstration session in sessionStorage so a page
 * reload does not eject the operator. No credential or token is stored.
 */
export function SessionBootstrap() {
  const dispatch = useAppDispatch();
  const session = useSession();

  useEffect(() => {
    let restored: { operatorId: string; role: string } | null = null;
    try {
      const raw = window.sessionStorage.getItem(STORAGE_KEY);
      restored = raw ? (JSON.parse(raw) as { operatorId: string; role: string }) : null;
    } catch {
      restored = null;
    }
    dispatch(
      hydrateSession(
        restored && typeof restored.operatorId === "string" && isRole(restored.role)
          ? { operatorId: restored.operatorId, role: restored.role }
          : null,
      ),
    );
  }, [dispatch]);

  useEffect(() => {
    if (!session.hydrated) return;
    try {
      if (session.signedIn) {
        window.sessionStorage.setItem(
          STORAGE_KEY,
          JSON.stringify({ operatorId: session.operatorId, role: session.role }),
        );
      } else {
        window.sessionStorage.removeItem(STORAGE_KEY);
      }
    } catch {
      // Storage is unavailable (private mode); the session stays in memory only.
    }
  }, [session.hydrated, session.signedIn, session.operatorId, session.role]);

  return null;
}
