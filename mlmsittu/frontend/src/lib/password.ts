/**
 * What counts as an acceptable password.
 *
 * <p>**This must stay in step with `PasswordPolicy.java`.** The server's answer is the one that
 * counts; this exists so the rule is visible while somebody types rather than arriving after a
 * round trip that clears the field. If the two drift, a password the form accepts is refused on
 * submit, which reads as the form being broken.
 */

export const MIN_LENGTH = 8;
export const MAX_LENGTH = 12;

export const PASSWORD_RULE =
  `Between ${MIN_LENGTH} and ${MAX_LENGTH} characters, with at least one letter and one number.`;

export function isAcceptablePassword(value: string): boolean {
  if (value.length < MIN_LENGTH || value.length > MAX_LENGTH) return false;
  return /[A-Za-z]/.test(value) && /[0-9]/.test(value);
}

/**
 * What is wrong with it, or null when nothing is.
 *
 * <p>One thing at a time, and the most obvious thing first. Listing every failure at once — "too
 * short, no number, no letter" — reads as a telling-off and is harder to act on than a single
 * instruction.
 *
 * <p>Returns null for an empty field: somebody who has not started typing has not made a mistake.
 */
export function passwordProblem(value: string): string | null {
  if (value === '') return null;
  if (value.length < MIN_LENGTH) {
    return `Too short — ${value.length} of ${MIN_LENGTH} characters.`;
  }
  if (value.length > MAX_LENGTH) {
    return `Too long — ${value.length} characters, and ${MAX_LENGTH} is the most allowed.`;
  }
  if (!/[A-Za-z]/.test(value)) return 'Add at least one letter.';
  if (!/[0-9]/.test(value)) return 'Add at least one number.';
  return null;
}
