/**
 * How a membership expiry is read at a glance.
 *
 * <p>One place, because the customer list and the customer profile must never disagree about
 * whether somebody is in trouble. Two independent thresholds is how a row shows amber on one
 * screen and red on the next.
 */

export type ExpiryBand = 'none' | 'expired' | 'urgent' | 'soon' | 'fine';

/** Inside a week is urgent; inside a month is worth seeing. Past is past. */
const URGENT_DAYS = 7;
const SOON_DAYS = 30;

export type ExpiryStatus = {
  band: ExpiryBand;
  /** Negative once it has passed, so "14 days overdue" is expressible. */
  days: number;
  label: string;
  /** Tailwind classes for a badge. Colour is never the only signal — the label says it too. */
  classes: string;
};

export function expiryStatus(expiresAt: string | null | undefined): ExpiryStatus {
  if (!expiresAt) {
    // Not approved yet, so there is nothing to expire. An em-dash rather than "never", which
    // would read as a promise.
    return { band: 'none', days: 0, label: '—', classes: 'text-ink3' };
  }

  const msPerDay = 24 * 60 * 60 * 1000;
  // Ceil, not round: something ending in 18 hours has "1 day" left, not none. Rounding down tells
  // somebody their membership ends today when it ends tomorrow morning.
  const days = Math.ceil((new Date(expiresAt).getTime() - Date.now()) / msPerDay);

  if (days < 0) {
    return {
      band: 'expired',
      days,
      label: `${Math.abs(days)}d overdue`,
      classes: 'border-danger bg-dangersoft text-danger font-semibold',
    };
  }
  if (days <= URGENT_DAYS) {
    return {
      band: 'urgent',
      days,
      label: days === 0 ? 'Ends today' : `${days}d left`,
      classes: 'border-warn bg-warnsoft text-warn font-semibold',
    };
  }
  if (days <= SOON_DAYS) {
    return {
      band: 'soon',
      days,
      label: `${days}d left`,
      classes: 'border-rulestrong bg-panel2 text-ink2',
    };
  }
  return {
    band: 'fine',
    days,
    label: new Date(expiresAt).toLocaleDateString(),
    classes: 'border-rule bg-panel text-ink3',
  };
}
