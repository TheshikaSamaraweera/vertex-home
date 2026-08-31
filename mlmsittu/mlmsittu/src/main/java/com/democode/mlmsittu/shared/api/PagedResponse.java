package com.democode.mlmsittu.shared.api;

import java.util.List;

/**
 * The frozen envelope shape for every list endpoint (development plan P0-05).
 *
 * <p>Cursor pagination arrived in Phase 7 (P7-03) and <b>this record did not change</b>, which is
 * what freezing the shape in Phase 0 bought: the paginated endpoints started returning a
 * {@code nextCursor} and no client had to be rewritten to cope.
 *
 * <p>A null {@code nextCursor} means "that was the last page" — including for endpoints that
 * return everything at once, which most still do because most lists are bounded by something
 * other than time. Callers follow the cursor when there is one and stop when there is not, and
 * need not know which kind of endpoint they are talking to.
 *
 * <p>Any list endpoint returning a bare JSON array instead of this record is a bug.
 *
 * <pre>
 * { "data": [ ... ], "nextCursor": null }
 * </pre>
 */
public record PagedResponse<T>(List<T> data, String nextCursor) {

    /** A single, complete page — no more data follows. */
    public static <T> PagedResponse<T> of(List<T> data) {
        return new PagedResponse<>(data, null);
    }

    /** A page with more data available beyond {@code nextCursor}. */
    public static <T> PagedResponse<T> of(List<T> data, String nextCursor) {
        return new PagedResponse<>(data, nextCursor);
    }

    /**
     * Builds a page from {@code limit + 1} rows fetched by the caller.
     *
     * <p>Fetching one extra row is how "is there another page?" gets answered without a second
     * count query — and a count would be a different question anyway, since it can change between
     * the two statements.
     *
     * @param fetched at most {@code limit + 1} rows, in order
     * @param cursorOf the marker for a row, called only on the last row actually returned
     */
    public static <T> PagedResponse<T> page(
            List<T> fetched, int limit, java.util.function.Function<T, Cursor> cursorOf) {
        if (fetched.size() <= limit) {
            return new PagedResponse<>(fetched, null);
        }
        List<T> page = List.copyOf(fetched.subList(0, limit));
        return new PagedResponse<>(page, cursorOf.apply(page.get(page.size() - 1)).encode());
    }
}
