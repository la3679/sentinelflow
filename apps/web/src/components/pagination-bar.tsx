import { ChevronLeft, ChevronRight } from "lucide-react";

import { Button } from "@/components/ui/button";
import type { PageMeta } from "@/domain/types";

/**
 * Paging controls over one page of a `Page<T>` response.
 *
 * **`page` is zero-based**, as it is in the contract and everywhere else in
 * this client. The `+ 1` happens once, here, where a person reads it — a
 * one-based page carried through the client and converted on the way out is
 * the sort of translation that outlives whoever remembers it.
 */
export function PaginationBar({
  meta,
  onPageChange,
  itemNoun,
}: {
  meta: PageMeta;
  onPageChange: (page: number) => void;
  itemNoun: string;
}) {
  const { page, size, totalElements, totalPages } = meta;
  const first = totalElements === 0 ? 0 : page * size + 1;
  const last = Math.min((page + 1) * size, totalElements);

  return (
    <nav
      aria-label={`${itemNoun} pagination`}
      className="flex flex-wrap items-center justify-between gap-3 border-t border-border px-4 py-3"
    >
      <p className="tabular text-xs text-muted-foreground" aria-live="polite">
        Showing {first}–{last} of {totalElements} {itemNoun}
      </p>
      <div className="flex items-center gap-2">
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={page <= 0}
          onClick={() => onPageChange(page - 1)}
        >
          <ChevronLeft aria-hidden="true" className="size-4" />
          Previous
        </Button>
        <span className="tabular text-xs text-muted-foreground">
          Page {page + 1} of {Math.max(1, totalPages)}
        </span>
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={page + 1 >= totalPages}
          onClick={() => onPageChange(page + 1)}
        >
          Next
          <ChevronRight aria-hidden="true" className="size-4" />
        </Button>
      </div>
    </nav>
  );
}
