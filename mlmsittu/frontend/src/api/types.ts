/**
 * Domain types used by the screens.
 *
 * These are hand-written aliases over the generated `schema.d.ts`, not a second source of truth.
 * The generated file is exhaustive and awkward to read (`components['schemas']['ItemResponse']`);
 * these give each shape a name and one place to look. Because they resolve *through* the generated
 * types, a backend field that changes still breaks the build here — which is the whole point of
 * generating in the first place.
 *
 * Regenerate with `npm run api:sync`.
 */
import type { components } from './schema';

type Schemas = components['schemas'];

// ---------------------------------------------------------------- identity

export type UserSummary = Schemas['UserSummary'];
export type LoginResponse = Schemas['LoginResponse'];

export const ROLES = [
  'SUPER_ADMIN',
  // Everything a super admin can do except manage users. Ordered by breadth, most privileged
  // first, which is the order the role picker shows them in.
  'ADMIN',
  'KYC_REVIEWER',
  'INVENTORY_CLERK',
  'PROCUREMENT_OFFICER',
  'FINANCE_OFFICER',
  'SUPPORT_AGENT',
  // Not staff. Held by everyone who signs up through the distributor front door, and the reason
  // the two applications can be told apart.
  'DISTRIBUTOR',
] as const;

export type Role = (typeof ROLES)[number];

// ---------------------------------------------------------------- catalogue

export type Item = Schemas['ItemResponse'];
export type Category = Schemas['CategoryResponse'];
export type ItemSet = Schemas['ItemSetResponse'];

// ---------------------------------------------------------------- inventory

export type StockLevel = Schemas['StockLevelResponse'];
export type StockByItem = Schemas['StockByItemResponse'];
export type StockInStore = Schemas['StockInStoreResponse'];
export type StoreDetail = Schemas['StoreDetailResponse'];
export type StoreItem = Schemas['StoreItemResponse'];
export type RewardEntitlement = Schemas['RewardEntitlementView'];

/** A print run of referral cards, and the individual cards in it. */
export type ReferralCardBatch = Schemas['ReferralCardBatch'];
export type ReferralCard = Schemas['ReferralCard'];
export type RewardStoreOption = Schemas['PackStoreOption'];
export type StockMovement = Schemas['StockMovementView'];
export type LocationSummary = Schemas['LocationResponse'];
export type ReorderAlert = Schemas['ReorderAlertResponse'];
export type SetAvailability = Schemas['SetAvailability'];
export type ComponentAvailability = Schemas['ComponentAvailability'];
export type Reservation = Schemas['ReservationView'];

// ---------------------------------------------------------------- procurement

export type Supplier = Schemas['SupplierResponse'];
export type PurchaseOrder = Schemas['PurchaseOrderResponse'];
export type PurchaseOrderLine = Schemas['PurchaseOrderLineResponse'];
export type GoodsReceipt = Schemas['GoodsReceiptResponse'];
export type SupplierPrice = Schemas['SupplierPriceResponse'];

// ---------------------------------------------------------------- helpers

/** Purchase order status values, in lifecycle order. */
export const PO_STATUSES = [
  'draft',
  'sent',
  'partially_received',
  'received',
  'cancelled',
] as const;
