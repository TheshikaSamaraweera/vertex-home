package com.democode.mlmsittu.shared.api;

import com.democode.mlmsittu.shared.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * An opaque page marker: where the last page stopped (P7-03).
 *
 * <h2>Why keyset and not offset</h2>
 *
 * {@code OFFSET 20} means "count twenty rows and throw them away" — it gets slower the deeper you
 * go, and it is <b>wrong</b> under concurrent writes. Insert a row that sorts before the current
 * page and every later page shifts by one: the reader sees one row twice and never sees another
 * at all. That is exactly what Gate 7 asks to be demonstrated, and it is not a corner case in a
 * catalogue several people edit at once.
 *
 * <p>A cursor instead records the sort key of the last row returned, and the next page asks for
 * rows <em>after</em> that key. Inserts before it are invisible; inserts after it appear in their
 * proper place. The cost does not grow with depth, because the index seeks straight there.
 *
 * <h2>Why the id is part of it</h2>
 *
 * Sorting by name alone is not a total order — two items can share one. The row after "Tea" is
 * then ambiguous, and an ambiguous boundary either repeats a row or skips one. Pairing the sort
 * column with the primary key makes the order total, so "after this exact row" has one answer.
 *
 * <h2>Why it is opaque</h2>
 *
 * Base64 rather than a readable key, so clients cannot construct one by hand and become dependent
 * on the sort column. Changing how a list is ordered should not be a breaking API change, and it
 * stays that way only while nobody outside can read the cursor.
 */
public record Cursor(String sortKey, UUID id) {

    /** A UUID's canonical form is always exactly this long, which is what lets the two split. */
    private static final int UUID_LENGTH = 36;

    /**
     * Encoded as {@code sortKey + uuid} with no delimiter.
     *
     * <p>A delimiter would need escaping — item names contain punctuation, and a name holding the
     * separator would split in the wrong place. The trailing 36 characters are always the id, so
     * no delimiter is needed and none can be spoofed.
     */
    public String encode() {
        String raw = (sortKey == null ? "" : sortKey) + id;
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @throws ApiException {@code INVALID_CURSOR} on anything that is not one of ours — a client
     *     that invented a cursor should be told so, not silently handed page one as though nothing
     *     had happened
     */
    public static Cursor decode(String encoded) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            if (raw.length() < UUID_LENGTH) {
                throw new IllegalArgumentException("too short to contain an id");
            }
            int split = raw.length() - UUID_LENGTH;
            return new Cursor(raw.substring(0, split), UUID.fromString(raw.substring(split)));
        } catch (RuntimeException malformed) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "That page marker is not valid.");
        }
    }

    /**
     * A marker for a list ordered by a single monotonic sequence, such as {@code stock_movement.id}.
     *
     * <p>No composite key is needed there: a BIGSERIAL is already a total order, so "everything
     * before this id" has exactly one meaning. Kept in this class rather than given its own so
     * that cursor opacity has one owner — a second scheme would eventually leak a readable key.
     */
    public static String encodeSequence(long lastId) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(Long.toString(lastId).getBytes(StandardCharsets.UTF_8));
    }

    /** @return null for an absent cursor, meaning the first page */
    public static Long decodeSequence(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(
                    new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8));
        } catch (RuntimeException malformed) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "That page marker is not valid.");
        }
    }

    /** Null and blank both mean "start at the beginning", so callers need no special case. */
    public static Cursor decodeOrNull(String encoded) {
        return encoded == null || encoded.isBlank() ? null : decode(encoded);
    }

    /**
     * Clamps a caller-supplied page size.
     *
     * <p>An unbounded {@code limit} is the same denial of service as having no pagination at all,
     * and a zero or negative one is a request for an infinite loop.
     */
    public static int clampLimit(Integer requested, int fallback, int max) {
        if (requested == null || requested <= 0) {
            return fallback;
        }
        return Math.min(requested, max);
    }
}
