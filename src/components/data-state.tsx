import { AlertTriangle, Inbox, RotateCcw } from "lucide-react";
import type { ReactNode } from "react";

import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import type { ApiError } from "@/mocks/mockBaseQuery";

function isApiError(value: unknown): value is ApiError {
  return typeof value === "object" && value !== null && "message" in value && "status" in value;
}

export function errorMessage(error: unknown): string {
  return isApiError(error) ? error.message : "The request could not be completed.";
}

export function LoadingBlock({ rows = 4, label }: { rows?: number | undefined; label: string }) {
  return (
    <div role="status" aria-live="polite" className="space-y-2 p-4">
      <span className="sr-only">{label}</span>
      {Array.from({ length: rows }, (_, i) => (
        <Skeleton key={i} className="h-8 w-full bg-muted" />
      ))}
    </div>
  );
}

export function ErrorBlock({
  error,
  onRetry,
  title = "Could not load this view",
}: {
  error: unknown;
  onRetry: () => void;
  title?: string;
}) {
  return (
    <div role="alert" className="flex flex-col items-start gap-3 p-6">
      <div className="flex items-start gap-2">
        <AlertTriangle aria-hidden="true" className="mt-0.5 size-5 text-risk-medium-foreground" />
        <div>
          <p className="font-semibold">{title}</p>
          <p className="text-sm text-muted-foreground">{errorMessage(error)}</p>
        </div>
      </div>
      <Button type="button" variant="outline" size="sm" onClick={onRetry}>
        <RotateCcw aria-hidden="true" className="size-4" />
        Retry
      </Button>
    </div>
  );
}

export function EmptyBlock({ title, hint }: { title: string; hint?: string | undefined }) {
  return (
    <div className="flex flex-col items-center gap-2 p-10 text-center">
      <Inbox aria-hidden="true" className="size-6 text-muted-foreground" />
      <p className="font-medium">{title}</p>
      {hint ? <p className="max-w-md text-sm text-muted-foreground">{hint}</p> : null}
    </div>
  );
}

interface QueryStateProps<T> {
  isLoading: boolean;
  isError: boolean;
  error: unknown;
  data: T | undefined;
  onRetry: () => void;
  loadingLabel: string;
  loadingRows?: number | undefined;
  isEmpty?: ((data: T) => boolean) | undefined;
  emptyTitle?: string | undefined;
  emptyHint?: string | undefined;
  children: (data: T) => ReactNode;
}

/** Single place where loading / error / empty / ready states are decided. */
export function QueryState<T>({
  isLoading,
  isError,
  error,
  data,
  onRetry,
  loadingLabel,
  loadingRows,
  isEmpty,
  emptyTitle = "No records match the current filters",
  emptyHint,
  children,
}: QueryStateProps<T>) {
  if (isLoading || data === undefined) {
    if (isError) return <ErrorBlock error={error} onRetry={onRetry} />;
    return <LoadingBlock rows={loadingRows} label={loadingLabel} />;
  }
  if (isError) return <ErrorBlock error={error} onRetry={onRetry} />;
  if (isEmpty?.(data)) return <EmptyBlock title={emptyTitle} hint={emptyHint} />;
  return <>{children(data)}</>;
}
