/**
 * How many referrals complete the programme.
 *
 * Raised from four to five on 28 Aug 2026. It lived as a bare `4` in ten places across six files,
 * which is why it gets a name now — the next change should be one edit, not a hunt.
 *
 * The server is still the authority: anything that receives `totalStages` from the API should
 * prefer it and fall back to this. This exists for the screens that have no stage object to hand,
 * such as a referral-places counter.
 */
export const REFERRAL_STAGES = 5;

/** `[0, 1, ... n-1]`, for rendering one marker per stage. */
export const stageIndexes = (total: number = REFERRAL_STAGES) =>
  Array.from({ length: total }, (_, index) => index);
