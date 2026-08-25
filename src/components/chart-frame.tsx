import { useEffect, useState, type ReactNode } from "react";

import { Skeleton } from "@/components/ui/skeleton";

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
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);

  if (!mounted) {
    return <Skeleton className="w-full bg-muted" style={{ height }} aria-hidden="true" />;
  }

  return (
    <figure className="m-0" style={{ height }} role="img" aria-label={label}>
      {children}
    </figure>
  );
}
