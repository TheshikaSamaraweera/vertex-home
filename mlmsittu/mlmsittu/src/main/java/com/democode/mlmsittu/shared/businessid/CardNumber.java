package com.democode.mlmsittu.shared.businessid;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The number printed on a referral card.
 *
 * <p>Cards are sold. Somebody pays for one, types the number on it, and joins the network — which
 * makes the number a bearer token, and a bearer token has to be unguessable.
 *
 * <p>It used to be the Business ID the buyer would receive: card 3 for parent {@code 12} read
 * {@code 123}. That is a function of the parent and a digit 1-5, so holding one card told you the
 * other four. Fine for a printed reference; useless the moment the paper is worth money.
 *
 * <h2>The alphabet</h2>
 *
 * <p>Thirty-one characters, with {@code O}, {@code 0}, {@code I}, {@code 1} and {@code L} left
 * out. These numbers are read aloud down a phone, copied onto a slip at a desk, and typed by
 * people who are not comfortable with computers — and the pairs above are the ones that get
 * confused. Dropping them costs a little entropy and removes a whole category of support call.
 *
 * <p>Eight characters from that alphabet is about 40 bits: roughly 8×10¹¹ possibilities. Against
 * the few hundred cards this business will ever print, a guess is not going to land. The hyphen is
 * decoration for the eye and is stripped before anything is compared.
 */
public final class CardNumber {

    /** No O, 0, I, 1 or L. See the class javadoc. */
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private static final int LENGTH = 8;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** What a card number looks like once normalised: eight characters from the alphabet. */
    private static final Pattern SHAPE = Pattern.compile("^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{8}$");

    private CardNumber() {}

    /**
     * A fresh number, canonical: eight characters, no separator.
     *
     * <p>Stored exactly as returned. The hyphen in {@code K7M2-P4X9} is decoration for the eye and
     * is added by {@link #format} at the moment of display — storing it would mean every lookup
     * had to strip it from the column as well as from the input, and the unique index could not
     * be a simple one over {@code upper(code)}.
     *
     * <p>{@link SecureRandom}, not {@code Math.random}. A predictable sequence here would let
     * somebody who bought one card work out the numbers of cards they did not buy, which is the
     * whole thing this class exists to prevent.
     */
    public static String generate() {
        StringBuilder drawn = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            drawn.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return drawn.toString();
    }

    /**
     * Strips everything a person might add, and upper-cases.
     *
     * <p>Somebody reading {@code K7M2-P4X9} off a card may type it lower case, with spaces instead
     * of the hyphen, or with no separator at all. Every one of those is the same number, and
     * refusing any of them is a support call about a card that works.
     */
    public static String normalise(String candidate) {
        if (candidate == null) {
            return "";
        }
        return candidate.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    /** True when {@code candidate} could be a card number. Says nothing about whether one exists. */
    public static boolean isValid(String candidate) {
        return SHAPE.matcher(normalise(candidate)).matches();
    }

    /** {@code K7M2P4X9} → {@code K7M2-P4X9}, for display and for print. */
    public static String format(String normalised) {
        String clean = normalise(normalised);
        return clean.length() == LENGTH ? clean.substring(0, 4) + "-" + clean.substring(4) : clean;
    }
}
