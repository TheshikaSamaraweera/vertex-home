/**
 * An issued item pack's four tracking stages, and how each reads.
 *
 * One place, so the customer's dashboard and the office's tracking page never describe the same
 * pack differently. The words for the last two stages depend on whether it is being picked up or
 * delivered.
 */

export const PACK_STAGES = ['awaiting_method', 'preparing', 'dispatched', 'completed'] as const;

export type PackStage = (typeof PACK_STAGES)[number];

export type ReceiveMethod = 'pickup' | 'delivery';

export function stageIndex(stage: string | null | undefined): number {
  return Math.max(0, PACK_STAGES.indexOf((stage ?? 'awaiting_method') as PackStage));
}

/** Short title for a stage — the stepper label and the table badge. */
export function stageTitle(stage: string | null | undefined, method?: string | null): string {
  const pickup = method === 'pickup';
  switch (stage) {
    case 'awaiting_method':
      return 'Choose receiving method';
    case 'preparing':
      return 'Preparing';
    case 'dispatched':
      return method ? (pickup ? 'Ready for pickup' : 'Out for delivery') : 'Pickup / delivery';
    case 'completed':
      return method ? (pickup ? 'Picked up' : 'Delivered') : 'Handed over';
    default:
      return stage ?? '—';
  }
}

/** One line explaining a stage to the customer. */
export function stageDescription(stage: string | null | undefined, method?: string | null): string {
  const pickup = method === 'pickup';
  switch (stage) {
    case 'awaiting_method':
      return 'Tell us how you would like to receive your pack — pick it up from a warehouse, or have it delivered.';
    case 'preparing':
      return 'The warehouse is getting your pack ready.';
    case 'dispatched':
      return pickup
        ? 'Your pack is waiting for you at the warehouse. Bring your NIC to collect it.'
        : 'Your pack is on its way. The driver will call the contact number you gave.';
    case 'completed':
      return pickup ? 'You collected your pack. Enjoy it!' : 'Your pack was delivered. Enjoy it!';
    default:
      return '';
  }
}

export const methodLabel = (method?: string | null) =>
  method === 'pickup' ? 'Pickup from warehouse' : method === 'delivery' ? 'Home delivery' : 'Not chosen yet';

/** Badge tone per stage, for tables. */
export function stageTone(stage: string | null | undefined): 'warn' | 'brand' | 'ok' | 'neutral' {
  switch (stage) {
    case 'awaiting_method':
      return 'warn';
    case 'preparing':
    case 'dispatched':
      return 'brand';
    case 'completed':
      return 'ok';
    default:
      return 'neutral';
  }
}
