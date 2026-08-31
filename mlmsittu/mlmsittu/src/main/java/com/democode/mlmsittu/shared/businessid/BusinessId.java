package com.democode.mlmsittu.shared.businessid;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The user-facing distributor identifier (architecture §2.1).
 *
 * <p>Format {@code SLV-XXXXX-C}: fixed prefix, five characters of Crockford-style Base31, and a
 * check character.
 *
 * <p>The alphabet excludes <b>I, L, O and U</b> — no 1/I confusion, no 0/O confusion, no accidental
 * profanity — and decoding is forgiving in the directions humans actually err: {@code I} and
 * {@code l} read as {@code 1}, {@code O} as {@code 0}.
 *
 * <p>IDs come from a sequence, not a random source: uniqueness with no collision retries. Allocated
 * at approval only, and never reissued once retired.
 *
 * <h2>Why 31 symbols and a modular check, rather than Base32 with Damm</h2>
 *
 * The architecture specifies a Damm check character over 32 symbols. Damm's guarantee — all single
 * character errors and all adjacent transpositions — needs a verified totally anti-symmetric
 * quasigroup of order 32, and a table with a subtle error in it silently fails to detect the very
 * mistakes it exists for. No modular scheme can substitute at base 32: single-error detection
 * requires odd weights, every difference of two odd weights is even, and an even multiplier
 * annihilates a digit difference of 16 — so transpositions of characters 16 apart pass unnoticed.
 *
 * <p>Dropping one symbol makes the alphabet size <b>prime</b>, and over a prime modulus a weighted
 * sum with distinct weights provably detects:
 *
 * <ul>
 *   <li>every single-character error, in the body or the check character itself;
 *   <li>every transposition of two characters — not only adjacent ones, which is <em>stronger</em>
 *       than Damm's guarantee.
 * </ul>
 *
 * <p>Both properties follow from 31 being prime, and {@code BusinessIdTest} verifies them
 * exhaustively rather than by assertion. The cost is capacity: 31⁵ ≈ 28.6 million instead of 33.5
 * million, against a requirement of "tens of thousands of users".
 */
public final class BusinessId {

    /** Crockford's alphabet minus its final symbol, giving a prime count. */
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXY".toCharArray();

    /** Prime. Every property below depends on this. */
    private static final int MODULUS = 31;

    private static final int BODY_LENGTH = 5;

    /** Distinct and non-zero mod 31, which is all the proof requires of them. */
    private static final int[] WEIGHTS = {2, 3, 4, 5, 6};

    private static final Pattern SHAPE =
            Pattern.compile("^SLV-([0-9A-HJKMNP-TV-Y]{5})-([0-9A-HJKMNP-TV-Y])$");

    /** 31^5 − 1. Beyond this the body would wrap and reissue somebody else's identifier. */
    public static final long MAX_SEQUENCE_VALUE = 28_629_150L;

    private BusinessId() {}

    /** Encodes a sequence value into a complete, check-digited Business ID. */
    public static String encode(long sequenceValue) {
        if (sequenceValue < 0) {
            throw new IllegalArgumentException("Sequence value must not be negative");
        }
        if (sequenceValue > MAX_SEQUENCE_VALUE) {
            // Stopping is the right failure. Wrapping would silently mint an ID that already
            // belongs to an existing distributor.
            throw new IllegalStateException("Business ID space exhausted");
        }

        StringBuilder body = new StringBuilder();
        long remaining = sequenceValue;
        for (int index = 0; index < BODY_LENGTH; index++) {
            body.insert(0, ALPHABET[(int) (remaining % MODULUS)]);
            remaining /= MODULUS;
        }

        String encoded = body.toString();
        return "SLV-" + encoded + "-" + checkCharacter(encoded);
    }

    /**
     * @return true when the shape is right <em>and</em> the check character agrees.
     *     <p>A typo produces an <em>invalid</em> ID rather than a different valid one. That is the
     *     entire point: a mistyped referrer must be rejected, not silently attach an applicant to
     *     the wrong upline where nobody would ever notice.
     */
    public static boolean isValid(String candidate) {
        if (candidate == null) {
            return false;
        }
        var matcher = SHAPE.matcher(normalise(candidate));
        return matcher.matches()
                && checkCharacter(matcher.group(1)) == matcher.group(2).charAt(0);
    }

    private static final String PREFIX = "SLV-";

    /**
     * Upper-cases and maps the substitutions people make by eye — Crockford's own recommendation.
     * Turns a common transcription slip into a successful lookup rather than a support call.
     *
     * <p>Applied to the identifier only, never the prefix. {@code SLV} contains an <b>L</b>, one of
     * the very characters the alphabet excludes, so normalising the whole string rewrites the
     * prefix to {@code S1V} and every valid ID fails to parse.
     */
    public static String normalise(String candidate) {
        String trimmed = candidate.trim().toUpperCase(Locale.ROOT);
        if (!trimmed.startsWith(PREFIX)) {
            // Let the shape check reject it rather than trying to repair something unrecognisable.
            return trimmed;
        }
        String remainder =
                trimmed.substring(PREFIX.length())
                        .replace('I', '1')
                        .replace('L', '1')
                        .replace('O', '0');
        return PREFIX + remainder;
    }

    static char checkCharacter(String body) {
        int sum = 0;
        for (int index = 0; index < body.length(); index++) {
            int digit = decodeChar(body.charAt(index));
            if (digit < 0) {
                throw new IllegalArgumentException("Character outside the alphabet");
            }
            sum += WEIGHTS[index] * digit;
        }
        return ALPHABET[sum % MODULUS];
    }

    static int decodeChar(char character) {
        for (int index = 0; index < ALPHABET.length; index++) {
            if (ALPHABET[index] == character) {
                return index;
            }
        }
        return -1;
    }

    static char[] alphabet() {
        return ALPHABET.clone();
    }
}
