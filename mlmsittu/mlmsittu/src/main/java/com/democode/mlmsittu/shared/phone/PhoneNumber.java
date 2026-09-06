package com.democode.mlmsittu.shared.phone;

import java.util.regex.Pattern;

/**
 * Mobile numbers, in one canonical form.
 *
 * <p>A number is now a login identifier, which changes what it has to be. An address book can hold
 * {@code 077 123 4567} and {@code +94771234567} as two spellings of one number and nobody minds. A
 * login cannot: the person typing it will not spell it the way they did at registration, and a
 * uniqueness index that treats those as different lets the same phone register twice.
 *
 * <p>So every number is stored in one shape — {@code +94771234567} — and every number offered at
 * login is put in that shape before it is looked up.
 *
 * <h2>What is accepted</h2>
 *
 * <pre>
 *   0771234567       local, with the trunk 0
 *   771234567        local, without it
 *   94771234567      country code, no plus
 *   +94771234567     canonical
 *   077-123 4567     any of the above with spaces, dashes, dots, brackets or slashes
 * </pre>
 *
 * <p>All of those become {@code +94771234567}. A number already carrying some other country code —
 * {@code +9715...} — is kept as it is, because a customer with an overseas number is a real case
 * and rewriting it to Sri Lanka would be worse than leaving it alone.
 *
 * <h2>What is not</h2>
 *
 * <p>Letters, an empty string, or a length that cannot be a real number. This deliberately does not
 * check that the number is <em>reachable</em>, only that it is well formed — there is no SMS
 * provider configured, so nothing here can know, and refusing a valid customer at the desk because
 * a carrier prefix is unrecognised is the worse failure.
 */
public final class PhoneNumber {

    /** Sri Lanka. Local forms are expanded to this. */
    private static final String DEFAULT_COUNTRY_CODE = "94";

    /** Separators people write numbers with. */
    private static final Pattern SEPARATORS = Pattern.compile("[\\s.()\\-/]");

    /** A subscriber number without country code or trunk prefix: 9 digits in Sri Lanka. */
    private static final Pattern LOCAL_SUBSCRIBER = Pattern.compile("^\\d{9}$");

    /** E.164 allows up to 15 digits after the plus, and no real number is shorter than 7. */
    private static final Pattern CANONICAL = Pattern.compile("^\\+\\d{7,15}$");

    private PhoneNumber() {}

    /**
     * The canonical form of {@code candidate}, or null if it is blank.
     *
     * @throws IllegalArgumentException if it is not blank and not a usable number
     */
    public static String normalise(String candidate) {
        if (candidate == null) {
            return null;
        }

        String digits = SEPARATORS.matcher(candidate.trim()).replaceAll("");
        if (digits.isEmpty()) {
            return null;
        }

        // An explicit country code is the author's intent. Keep it.
        if (digits.startsWith("+")) {
            String rest = digits.substring(1);
            require(rest.chars().allMatch(Character::isDigit), candidate);
            return check("+" + rest, candidate);
        }

        require(digits.chars().allMatch(Character::isDigit), candidate);

        // 0771234567 — the trunk prefix is a domestic dialling convention and is not part of the
        // number. Dropping it before prepending the country code is the whole conversion.
        if (digits.startsWith("0")) {
            return check("+" + DEFAULT_COUNTRY_CODE + digits.substring(1), candidate);
        }

        // 94771234567 — already carries the country code, just without the plus.
        if (digits.startsWith(DEFAULT_COUNTRY_CODE) && digits.length() > 9) {
            return check("+" + digits, candidate);
        }

        // 771234567 — bare subscriber number.
        if (LOCAL_SUBSCRIBER.matcher(digits).matches()) {
            return check("+" + DEFAULT_COUNTRY_CODE + digits, candidate);
        }

        throw new IllegalArgumentException(
                "'" + candidate + "' is not a phone number this can read.");
    }

    /** True when {@code candidate} is blank or a number that normalises. */
    public static boolean isValid(String candidate) {
        try {
            normalise(candidate);
            return true;
        } catch (IllegalArgumentException rejected) {
            return false;
        }
    }

    /**
     * True when the value looks like somebody meant a phone number rather than an email address.
     *
     * <p>Used to route one login field to the right lookup. Not a validity check: {@code 07712} is
     * plainly a phone number and plainly wrong, and the honest answer to it is "no such account",
     * not "that is not a phone number" — which would tell an attacker which spellings exist.
     */
    public static boolean looksLikePhoneNumber(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        String stripped = SEPARATORS.matcher(candidate.trim()).replaceAll("");
        if (stripped.startsWith("+")) {
            stripped = stripped.substring(1);
        }
        return !stripped.isEmpty() && stripped.chars().allMatch(Character::isDigit);
    }

    private static String check(String normalised, String original) {
        require(CANONICAL.matcher(normalised).matches(), original);
        return normalised;
    }

    private static void require(boolean condition, String original) {
        if (!condition) {
            throw new IllegalArgumentException(
                    "'" + original + "' is not a phone number this can read.");
        }
    }
}
