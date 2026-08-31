package com.democode.mlmsittu.shared.businessid;

import java.util.regex.Pattern;

/**
 * The distributor identifier, which encodes the person's position in the referral tree.
 *
 * <p>A root customer is a single digit. Every child is its parent's identifier with the child's
 * seat number appended:
 *
 * <pre>
 *   1                     the first root customer
 *   11 12 13 14 15        that customer's five children
 *   121 … 125             the children of 12
 *   1431 … 1435           the children of 143
 * </pre>
 *
 * <p>So the identifier <em>is</em> the path. {@code 1432} is unambiguously the second child of the
 * third child of the fourth child of root 1 — no lookup, no join. Reading an ID tells you the depth
 * (its length) and the whole ancestry (every prefix).
 *
 * <h2>How more than nine roots stay unambiguous</h2>
 *
 * <p>Seats are numbered 1-5, so those five digits are spoken for. Root identifiers therefore use
 * <b>only 0 and 6-9 after their first character</b>:
 *
 * <pre>
 *   1 2 3 … 9        the first nine roots, single digits
 *   10 16 17 18 19   the tenth onwards
 *   20 26 27 28 29   …
 * </pre>
 *
 * <p>That one rule makes concatenation unambiguous for any number of roots. {@code 161} can only
 * be root {@code 16} seat 1 — it cannot be root {@code 1} seat 6, because there is no seat 6, and
 * it cannot be root {@code 161}, because a root may not contain a 1 after its first character.
 * Reading an identifier is therefore mechanical: take the leading digit and every following
 * 0-or-6-to-9 as the root, and everything after it is the chain of seats.
 *
 * <p>A naive scheme numbering roots 1, 2, 3 … 11 would collide immediately: root {@code 11}'s
 * first child and root {@code 1}'s seat-1-then-seat-1 grandchild are both {@code 111}, and nothing
 * afterwards could say which person that meant.
 *
 * <h2>Depth</h2>
 *
 * <p>One character per generation, so a 20-deep chain is a 21-character identifier. That is fine to
 * store and unpleasant to read aloud, so depth is capped — see {@link #MAX_DEPTH}. The cap exists
 * to fail clearly at a sane boundary rather than to enforce a business rule.
 */
public final class PositionalId {

    /** Seats under any one parent. Matches the referral cap. */
    public static final int SEATS = 5;

    /** Digits a root may use after its first character. Disjoint from the seat digits. */
    private static final String ROOT_TAIL = "06789";

    /**
     * Generations below a root. 40 gives an identifier of 41 characters, comfortably inside the
     * column, and is far beyond any real network.
     */
    public static final int MAX_DEPTH = 40;

    /** A root, then seats. Deliberately strict: no spaces, no prefix, no letters. */
    private static final Pattern SHAPE =
            Pattern.compile("^[1-9][06-9]*[1-" + SEATS + "]{0," + MAX_DEPTH + "}$");

    /** A root on its own: leading digit, then only the tail digits. */
    private static final Pattern ROOT_SHAPE = Pattern.compile("^[1-9][06-9]*$");

    private PositionalId() {}

    /** The identifier of the {@code seat}-th child of {@code parentId}. */
    public static String child(String parentId, int seat) {
        if (seat < 1 || seat > SEATS) {
            throw new IllegalArgumentException("Seat must be 1.." + SEATS + ", was " + seat);
        }
        return parentId + seat;
    }

    /**
     * The {@code position}-th root identifier, 1-based.
     *
     * <p>Counting in a mixed radix: nine choices for the first digit, five for each digit after it.
     * So 1-9 are single digits, 10-54 are two digits, and so on — the common case is exactly the
     * numbering a person would expect, and the unusual case simply keeps going.
     */
    public static String root(int position) {
        if (position < 1) {
            throw new IllegalArgumentException("Root position must be 1 or more, was " + position);
        }

        int remaining = position - 1;
        StringBuilder tail = new StringBuilder();

        // Peel off tail digits until what is left fits in the leading digit's nine values.
        while (remaining >= 9) {
            remaining -= 9;
            tail.insert(0, ROOT_TAIL.charAt(remaining % ROOT_TAIL.length()));
            remaining /= ROOT_TAIL.length();
        }
        return (remaining + 1) + tail.toString();
    }

    /** True when this identifier is a root — no seats, so nobody referred them. */
    public static boolean isRoot(String id) {
        return id != null && ROOT_SHAPE.matcher(id).matches();
    }

    /** The root part: the leading digit and every 0-or-6-to-9 that follows it. */
    private static int rootLength(String id) {
        int length = 1;
        while (length < id.length() && ROOT_TAIL.indexOf(id.charAt(length)) >= 0) {
            length++;
        }
        return length;
    }

    public static boolean isValid(String candidate) {
        return candidate != null && SHAPE.matcher(candidate.trim()).matches();
    }

    /**
     * Trims and strips separators people add on their own.
     *
     * <p>Somebody reading {@code 1431} off a card may well write it as {@code 1-4-3-1} or
     * {@code 1 4 3 1}, because it plainly has structure. Accepting that costs nothing and the
     * alternative is a support call.
     */
    public static String normalise(String candidate) {
        if (candidate == null) {
            return "";
        }
        return candidate.replaceAll("[\\s.\\-/]", "");
    }

    /** How many generations below a root. A root is depth 0. */
    public static int depth(String id) {
        return id == null || id.isEmpty() ? 0 : id.length() - rootLength(id);
    }

    /** The parent's identifier, or null for a root. */
    public static String parentOf(String id) {
        if (id == null || id.isEmpty() || depth(id) == 0) {
            return null;
        }
        return id.substring(0, id.length() - 1);
    }

    /** The seat this identifier occupies under its parent, or 0 for a root. */
    public static int seatOf(String id) {
        if (id == null || id.isEmpty() || depth(id) == 0) {
            return 0;
        }
        return Character.getNumericValue(id.charAt(id.length() - 1));
    }
}
