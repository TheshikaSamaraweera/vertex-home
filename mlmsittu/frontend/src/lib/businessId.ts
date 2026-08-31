/**
 * Business ID validation, client side.
 *
 * Architecture §2.1: "Validated client-side, so a mistyped parent ID never reaches the server."
 *
 * The identifier encodes the person's seat in the referral tree. A root is a single digit 1-9;
 * every child is its parent's ID with its seat number, 1-5, appended:
 *
 *     1                  the first root customer
 *     11 12 13 14 15     that customer's five children
 *     121 ... 125        the children of 12
 *     1431 ... 1435      the children of 143
 *
 * So the ID *is* the path — every prefix is an ancestor. There is no check character any more,
 * and none is possible: an ID's structure is its meaning, and appending a checksum would make
 * `12` and `123` unrelated strings rather than parent and child.
 *
 * **This must stay in step with `PositionalId.java`** — same shape, same limits. If the backend's
 * scheme changes and this does not, valid IDs start being rejected in the browser while the server
 * accepts them, which reads as a mysterious form bug.
 */

/** Seats under one parent. Matches the referral cap and `PositionalId.SEATS`. */
const SEATS = 5;

/** See `PositionalId.MAX_DEPTH`. */
const MAX_DEPTH = 40;

/**
 * A root, then seats.
 *
 * Roots use only 0 and 6-9 after their first digit, and seats only ever use 1-5, so the two can
 * never be confused however many roots exist. `161` is root 16, seat 1 — it cannot be root 1 seat
 * 6, because there is no seat 6. In practice there is one root, numbered `1`.
 */
const SHAPE = new RegExp(`^[1-9][06-9]*[1-${SEATS}]{0,${MAX_DEPTH}}$`);

/**
 * Strips the separators people add on their own.
 *
 * An ID like `1431` visibly has structure, so somebody copying it off a card may well write
 * `1-4-3-1` or `1 4 3 1`. Accepting that costs nothing, and refusing it earns a support call.
 */
export function normaliseBusinessId(candidate: string): string {
  return candidate.replace(/[\s.\-/]/g, '');
}

export function isValidBusinessId(candidate: string | null | undefined): boolean {
  if (!candidate) return false;
  return SHAPE.test(normaliseBusinessId(candidate));
}

/** What to tell the user, distinguishing "not finished typing" from "wrong". */
export type BusinessIdState =
  | { status: 'empty' }
  | { status: 'incomplete' }
  | { status: 'malformed'; message: string }
  | { status: 'failed-check'; message: string }
  | { status: 'valid'; normalised: string };

export function inspectBusinessId(raw: string): BusinessIdState {
  const value = raw.trim();
  if (!value) return { status: 'empty' };

  const normalised = normaliseBusinessId(value);

  // A single digit is a valid root ID, so there is no length at which someone is "not finished".
  // Only a first character that cannot start an ID is worth interrupting for.
  if (!/^[1-9]/.test(normalised)) {
    return {
      status: 'malformed',
      message: 'An ID starts with 1-9. Check it against the card.',
    };
  }

  if (!SHAPE.test(normalised)) {
    return {
      status: 'malformed',
      // Naming the actual rule beats "invalid": every character after the first is a seat number,
      // and seats only run 1 to 5. A 0, a 6-9 or a letter is a misread digit, not a wrong ID.
      message: `That is not a valid ID. Check it against the card.`,
    };
  }

  return { status: 'valid', normalised };
}
