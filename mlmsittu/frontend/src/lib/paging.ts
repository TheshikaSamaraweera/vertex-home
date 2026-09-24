import { useEffect, useState } from 'react';

/**
 * Rows per page on every screen that pages. Twenty rows fill a laptop screen without scrolling
 * far, and keep each response small however large the list behind it grows.
 */
export const PAGE_SIZE = 20;

/**
 * Where a paged list is up to.
 *
 * The server hands out an opaque `nextCursor` and nothing else — keyset pagination has no "page
 * 7" to jump to and no "previous" cursor. Going back is done here instead: every cursor used on
 * the way forward is kept, so Previous is just stepping back along that trail.
 *
 * Anything in `resetOn` that changes — a search term, a filter — starts again from the first
 * page. A cursor is only meaningful under the query that issued it, and reusing one under a new
 * search would land somewhere arbitrary in the new result.
 */
export function usePager(...resetOn: unknown[]) {
  const [trail, setTrail] = useState<(string | undefined)[]>([undefined]);

  const resetKey = JSON.stringify(resetOn);
  const [lastResetKey, setLastResetKey] = useState(resetKey);
  if (resetKey !== lastResetKey) {
    // Adjusting state during render, rather than in an effect, so the stale cursor is never
    // requested under the new filter even for one frame.
    setLastResetKey(resetKey);
    setTrail([undefined]);
  }

  return {
    /** The cursor for the page being shown; undefined for the first. */
    cursor: trail[trail.length - 1],
    /** 1-based, for display. */
    page: trail.length,
    next: (nextCursor: string) => setTrail((current) => [...current, nextCursor]),
    previous: () => setTrail((current) => (current.length > 1 ? current.slice(0, -1) : current)),
  };
}

/**
 * `value`, once it has stopped changing for `delayMs`.
 *
 * Search boxes on paged screens query the server, and a request per keystroke would be several
 * requests for one word — most of them for results nobody will see.
 */
export function useDebounced<T>(value: T, delayMs = 300): T {
  const [settled, setSettled] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setSettled(value), delayMs);
    return () => clearTimeout(timer);
  }, [value, delayMs]);
  return settled;
}
