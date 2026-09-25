import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  useCategories,
  useCreateCategory,
  itemImageUrl,
  uploadItemImage,
  useCreateItem,
  useDeleteItem,
  useItemsPage,
  useLocations,
  useRemoveSupplierPrice,
  useSetItemActive,
  useSetSupplierPrice,
  useStockForItems,
  useSupplierPrices,
  useSuppliers,
  useUpdateItem,
} from '../api/queries';
import type { Item } from '../api/types';
import { Icon } from '../components/icons';
import { useDebounced, usePager } from '../lib/paging';
import { ApiError } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import {
  AvailabilityBox,
  Badge,
  Button,
  Card,
  EmptyState,
  ErrorBanner,
  Field,
  Input,
  Modal,
  money,
  PageHeader,
  Pager,
  Select,
  Spinner,
  Table,
  TableWrap,
  Td,
  Th,
} from '../components/ui';

export function ItemsPage() {
  const { t } = useTranslation();
  const { hasRole } = useAuth();
  const canWrite = hasRole('INVENTORY_CLERK');

  const [includeInactive, setIncludeInactive] = useState(false);
  const [search, setSearch] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<Item | null>(null);
  const [confirmingDelete, setConfirmingDelete] = useState<Item | null>(null);
  const [creatingCategory, setCreatingCategory] = useState(false);

  const remove = useDeleteItem();

  const canManageCategories = hasRole('ADMIN');

  // One page at a time, searched on the server. Filtering in the browser only works while the
  // browser holds the whole catalogue, and holding the whole catalogue is what made this slow.
  const query = useDebounced(search.trim());
  const pager = usePager(includeInactive, query, categoryId);
  const items = useItemsPage(includeInactive, query, categoryId, pager.cursor);
  const filtered = search !== '' || categoryId !== '' || includeInactive;
  const categories = useCategories();

  const rows = items.data?.data ?? [];

  // Availability is a stock fact, not a catalogue one, so it comes from the ledger and is joined
  // here. Summed across locations: an item held in two warehouses has as much available as the two
  // together, and a per-location figure would need a location picker this table does not want.
  // Asked for this page's items only.
  const stock = useStockForItems(rows.map((item) => item.id ?? '').filter(Boolean));
  const availableByItem = useMemo(() => {
    const totals = new Map<string, number>();
    for (const level of stock.data ?? []) {
      if (!level.itemId) continue;
      const available = (level.onHand ?? 0) - (level.reserved ?? 0);
      totals.set(level.itemId, (totals.get(level.itemId) ?? 0) + available);
    }
    return totals;
  }, [stock.data]);

  const categoryName = (id: string | null | undefined) =>
    (categories.data ?? []).find((category) => category.id === id)?.name ?? '—';

  return (
    <>
      <PageHeader
        title={t('Items')}
        description={t('Everything sold or stocked. Availability is stock on hand less anything already reserved.')}
        actions={
          <>
            {/* Categories are the shape every report groups by, so creating one is an admin
                decision rather than a convenience. The server agrees. */}
            {canManageCategories && (
              <Button size="sm" onClick={() => setCreatingCategory(true)}>
                {t('New category')}
              </Button>
            )}
            {canWrite && (
              <Button variant="primary" size="sm" onClick={() => setCreating(true)}>
                {t('New item')}
              </Button>
            )}
          </>
        }
      />

      {/* Usually ITEM_IN_USE: the item has a past, and the answer is to deactivate it. */}
      {remove.error != null && (
        <div className="mb-4">
          <ErrorBanner error={remove.error} />
        </div>
      )}

      <Card
        title={t('Catalogue')}
        subtitle={t('{{count}} shown', { count: rows.length })}
        actions={
          <>
            <Input
              className="w-64"
              type="search"
              aria-label={t('Search items')}
              placeholder={t('Search by item name or item code…')}
              value={search}
              onChange={(event) => setSearch(event.target.value)}
            />
            <Select
              className="w-44"
              aria-label={t('Category')}
              value={categoryId}
              onChange={(event) => setCategoryId(event.target.value)}
            >
              <option value="">{t('All categories')}</option>
              {(categories.data ?? []).map((category) => (
                <option key={category.id} value={category.id ?? ''}>
                  {category.name}
                </option>
              ))}
            </Select>
            <label className="flex items-center gap-2 text-xs text-ink2">
              <input
                type="checkbox"
                checked={includeInactive}
                onChange={(event) => setIncludeInactive(event.target.checked)}
              />
              {t('Show deactivated')}
            </label>
            {filtered && (
              <Button
                size="sm"
                variant="ghost"
                onClick={() => {
                  setSearch('');
                  setCategoryId('');
                  setIncludeInactive(false);
                }}
              >
                {t('Clear filters')}
              </Button>
            )}
          </>
        }
      >
        {items.isLoading ? (
          <Spinner />
        ) : items.error ? (
          <div className="p-4">
            <ErrorBanner error={items.error} onRetry={() => void items.refetch()} />
          </div>
        ) : rows.length === 0 ? (
          <EmptyState message={t('Nothing matches.')} />
        ) : (
          <TableWrap>
            <Table>
              <thead>
                <tr>
                  <Th>{t('Item code')}</Th>
                  <Th>{t('Name')}</Th>
                  <Th>{t('Category')}</Th>
                  <Th align="right">{t('Available')}</Th>
                  <Th align="right">{t('Cost')}</Th>
                  {/* Quoted prices. Everyone reads them; only a super admin changes them. */}
                  <Th align="right">{t('Retail')}</Th>
                  <Th align="right">{t('Wholesale')}</Th>
                  <Th align="right">{t('Reorder at')}</Th>
                  {canWrite && <Th>{''}</Th>}
                </tr>
              </thead>
              <tbody>
                {rows.map((item) => {
                  const cost = Number(item.unitCost ?? 0);
                  return (
                    <tr key={item.id} className={item.active ? 'hover:bg-panel2' : 'opacity-60'}>
                      <Td className="font-mono text-xs">{item.sku}</Td>
                      <Td>
                        <span className="inline-flex items-center gap-2.5">
                          {item.imageId ? (
                            <img
                              src={itemImageUrl(item.imageId)}
                              alt=""
                              className="h-9 w-9 flex-none rounded-lg border border-rule object-cover"
                            />
                          ) : (
                            <span
                              aria-hidden
                              className="flex h-9 w-9 flex-none items-center justify-center rounded-lg bg-panel2 text-ink3"
                            >
                              <Icon name="package" className="h-4 w-4" />
                            </span>
                          )}
                          <span className="text-ink">{item.name}</span>
                        </span>
                        {!item.active && (
                          <Badge tone="danger">
                            <span className="ml-0">{t('deactivated')}</span>
                          </Badge>
                        )}
                      </Td>
                      <Td className="text-xs">{categoryName(item.categoryId)}</Td>
                      <Td align="right">
                        {stock.isLoading || stock.isPlaceholderData ? (
                          <span className="text-ink3">…</span>
                        ) : (
                          <AvailabilityBox available={availableByItem.get(item.id ?? '') ?? 0} />
                        )}
                      </Td>
                      <Td align="right">{money(cost)}</Td>
                      {/* An em dash rather than 0.00 — no price set is not a price of nothing. */}
                      <Td align="right" className="text-ink2">
                        {item.retailPrice != null ? money(item.retailPrice) : '—'}
                      </Td>
                      <Td align="right" className="text-ink2">
                        {item.wholesalePrice != null ? money(item.wholesalePrice) : '—'}
                      </Td>
                      <Td align="right" className="text-ink3">
                        {item.reorderLevel || '—'}
                      </Td>
                      {canWrite && (
                        <Td>
                          <div className="flex justify-end gap-1">
                            <Button size="sm" onClick={() => setEditing(item)}>
                              {t('Edit')}
                            </Button>
                            <ActiveToggle id={item.id} active={!!item.active} />
                            <Button
                              size="sm"
                              variant="danger"
                              onClick={() => {
                                remove.reset();
                                setConfirmingDelete(item);
                              }}
                            >
                              {t('Delete')}
                            </Button>
                          </div>
                        </Td>
                      )}
                    </tr>
                  );
                })}
              </tbody>
            </Table>
          </TableWrap>
        )}
        <Pager
          page={pager.page}
          hasNext={Boolean(items.data?.nextCursor)}
          loading={items.isPlaceholderData}
          onPrevious={pager.previous}
          onNext={() => items.data?.nextCursor && pager.next(items.data.nextCursor)}
        />
      </Card>

      {creating && <ItemModal onClose={() => setCreating(false)} />}
      {editing && <ItemModal existing={editing} onClose={() => setEditing(null)} />}
      {confirmingDelete && (
        <ConfirmDeleteModal
          item={confirmingDelete}
          pending={remove.isPending}
          onCancel={() => setConfirmingDelete(null)}
          onConfirm={() => {
            if (!confirmingDelete.id) return;
            // Closed either way: a refusal is shown above the table, where it stays readable
            // after the dialog has gone.
            remove.mutate(confirmingDelete.id, { onSettled: () => setConfirmingDelete(null) });
          }}
        />
      )}
      {creatingCategory && <CreateCategoryModal onClose={() => setCreatingCategory(false)} />}
    </>
  );
}

/** Deactivate / Activate. Hides an item from new orders without touching its history. */
function ActiveToggle({ id, active }: { id: string | undefined; active: boolean }) {
  const { t } = useTranslation();
  const toggle = useSetItemActive();
  return (
    <Button
      size="sm"
      variant="ghost"
      disabled={!id || toggle.isPending}
      onClick={() => id && toggle.mutate({ id, active: !active })}
    >
      {active ? t('Deactivate') : t('Activate')}
    </Button>
  );
}

function ConfirmDeleteModal({
  item,
  pending,
  onCancel,
  onConfirm,
}: {
  item: Item;
  pending: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const { t } = useTranslation();
  return (
    <Modal title={t('Delete this item?')} onClose={onCancel}>
      <div className="flex flex-col gap-4">
        <p className="text-sm text-ink2">
          {t('“{{name}}” ({{code}}) will be removed completely.', {
            name: item.name,
            code: item.sku,
          })}
        </p>
        <p className="text-xs text-ink3">
          {t('If it has ever been stocked, sold, ordered or put in a set, the deletion is refused — those records point here and would be orphaned. Deactivate instead: it stops new orders and keeps the history.')}
        </p>
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onCancel}>
            {t('Cancel')}
          </Button>
          <Button type="button" variant="danger" disabled={pending} onClick={onConfirm}>
            {pending ? t('Deleting…') : t('Delete item')}
          </Button>
        </div>
      </div>
    </Modal>
  );
}

/** Admins and super admins only, matching the server. */
function CreateCategoryModal({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  const create = useCreateCategory();
  const [form, setForm] = useState({ code: '', name: '' });

  return (
    <Modal title={t('New category')} onClose={onClose}>
      <form
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault();
          create.mutate(form, { onSuccess: onClose });
        }}
      >
        <p className="text-xs text-ink2">
          {t('Every report groups by category, so adding one changes how the numbers read for everybody. Keep the list short.')}
        </p>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label={t('Code')}>
            <Input
              required
              autoFocus
              value={form.code}
              onChange={(event) => setForm({ ...form, code: event.target.value })}
            />
          </Field>
          <Field label={t('Name')}>
            <Input
              required
              value={form.name}
              onChange={(event) => setForm({ ...form, name: event.target.value })}
            />
          </Field>
        </div>
        <ErrorBanner error={create.error} />
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            {t('Cancel')}
          </Button>
          <Button type="submit" variant="primary" disabled={create.isPending}>
            {t('Create category')}
          </Button>
        </div>
      </form>
    </Modal>
  );
}

type PriceRow = { supplierId: string; price: string; note: string };

const amount = (value: unknown) => (value == null ? '' : String(value));

/**
 * The item form, for a new item and for changing one.
 *
 * Three things the client asked for after the demo, each a little more than a field:
 *
 * - **Stock location and opening quantity.** The item is created with a real stock position, so it
 *   is sellable immediately. A non-zero opening balance goes through the ledger as a movement.
 *   Creation only — once an item exists, stock changes through receipts and adjustments, where
 *   they leave a trail.
 * - **Supplier prices.** Saved after the item, because they belong to procurement and need an
 *   item id to point at. If one fails the item is still saved, and the form says so rather than
 *   leaving the operator to guess whether to start again.
 * - **Retail and wholesale.** Visible to everyone, editable only by a super admin.
 *
 * The item code cannot be changed: orders, receipts and printed labels already carry it.
 */
function ItemModal({ existing, onClose }: { existing?: Item; onClose: () => void }) {
  const { t } = useTranslation();
  const { hasRole } = useAuth();
  const create = useCreateItem();
  const update = useUpdateItem();
  const setSupplierPrice = useSetSupplierPrice();
  const removeSupplierPrice = useRemoveSupplierPrice();
  const categories = useCategories();
  const locations = useLocations();
  const suppliers = useSuppliers();
  const currentPrices = useSupplierPrices(existing?.id ?? null);

  const editing = existing !== undefined;
  const canQuote = hasRole('SUPER_ADMIN');

  const [form, setForm] = useState({
    sku: existing?.sku ?? '',
    name: existing?.name ?? '',
    description: existing?.description ?? '',
    categoryId: existing?.categoryId ?? '',
    locationId: '',
    openingQuantity: '0',
    unitCost: amount(existing?.unitCost),
    retailPrice: amount(existing?.retailPrice),
    wholesalePrice: amount(existing?.wholesalePrice),
    reorderLevel: String(existing?.reorderLevel ?? 0),
  });

  // An edit starts from the prices already on file, once they have arrived. `null` until then, so
  // the rows are filled exactly once and never overwrite what the user has typed since.
  const [prices, setPrices] = useState<PriceRow[] | null>(editing ? null : []);
  if (prices === null && currentPrices.data) {
    setPrices(
      currentPrices.data.map((row) => ({
        supplierId: row.supplierId ?? '',
        price: amount(row.price),
        note: row.note ?? '',
      })),
    );
  }
  const rows = prices ?? [];
  const setRow = (index: number, patch: Partial<PriceRow>) =>
    setPrices(rows.map((row, i) => (i === index ? { ...row, ...patch } : row)));

  const [priceError, setPriceError] = useState<unknown>(null);

  // The picture is uploaded on its own, before the item is saved, and the item then carries its
  // id. Prefilled on an edit, because the form sends the whole item and an empty field would
  // delete the picture.
  const [imageId, setImageId] = useState<string | null>(existing?.imageId ?? null);
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState<unknown>(null);

  async function pickImage(file: File | undefined) {
    if (!file) return;
    setUploading(true);
    setUploadError(null);
    try {
      setImageId(await uploadItemImage(file));
    } catch (caught) {
      setUploadError(caught);
    } finally {
      setUploading(false);
    }
  }

  const saveError = editing ? update.error : create.error;
  const fieldErrors = saveError instanceof ApiError ? saveError.fieldErrors : {};
  const saving = create.isPending || update.isPending;
  const set = (key: keyof typeof form) => (value: string) =>
    setForm((previous) => ({ ...previous, [key]: value }));

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setPriceError(null);

    let itemId: string;
    if (existing?.id) {
      await update.mutateAsync({
        id: existing.id,
        body: {
          name: form.name,
          description: form.description || undefined,
          categoryId: form.categoryId || undefined,
          unitCost: Number(form.unitCost),
          // Always sent on an edit. The server refuses only an actual change from someone who may
          // not quote, and resending the stored figure is not one.
          retailPrice: form.retailPrice ? Number(form.retailPrice) : null,
          wholesalePrice: form.wholesalePrice ? Number(form.wholesalePrice) : null,
          reorderLevel: Number(form.reorderLevel),
          imageId,
        },
      });
      itemId = existing.id;
    } else {
      const item = await create.mutateAsync({
        sku: form.sku,
        name: form.name,
        description: form.description || undefined,
        categoryId: form.categoryId || undefined,
        locationId: form.locationId || undefined,
        openingQuantity: Number(form.openingQuantity || 0),
        unitCost: Number(form.unitCost),
        // Omitted rather than sent as null when the user may not set them. The server refuses a
        // *change*, and sending null for an item that has none is not a change.
        retailPrice: canQuote && form.retailPrice ? Number(form.retailPrice) : undefined,
        wholesalePrice: canQuote && form.wholesalePrice ? Number(form.wholesalePrice) : undefined,
        reorderLevel: Number(form.reorderLevel),
        imageId,
      });
      itemId = item.id!;
    }

    try {
      const kept = rows.filter((row) => row.supplierId && row.price);
      for (const row of kept) {
        await setSupplierPrice.mutateAsync({
          itemId,
          body: { supplierId: row.supplierId, price: Number(row.price), note: row.note || undefined },
        });
      }
      // A supplier whose row was removed, or switched to another supplier, stops being quoted.
      const keptIds = new Set(kept.map((row) => row.supplierId));
      for (const old of currentPrices.data ?? []) {
        if (old.supplierId && !keptIds.has(old.supplierId)) {
          await removeSupplierPrice.mutateAsync({ itemId, supplierId: old.supplierId });
        }
      }
      onClose();
    } catch (error) {
      setPriceError(error);
    }
  }

  return (
    <Modal title={editing ? t('Edit item') : t('New item')} onClose={onClose} wide>
      <form className="flex flex-col gap-4" onSubmit={(event) => void submit(event)}>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field
            label={t('Item code')}
            hint={
              editing
                ? t('Fixed once created — orders and labels already carry it')
                : t('Stored upper case — slv-001 and SLV-001 are one item')
            }
            error={fieldErrors.sku}
          >
            <Input
              required
              autoFocus={!editing}
              disabled={editing}
              value={form.sku}
              onChange={(event) => set('sku')(event.target.value)}
            />
          </Field>
          <Field label={t('Name')} error={fieldErrors.name}>
            <Input
              required
              autoFocus={editing}
              value={form.name}
              onChange={(event) => set('name')(event.target.value)}
            />
          </Field>
        </div>

        <Field label={t('Description')} hint={t('What it is, in the words a customer would use')}>
          <Input
            value={form.description}
            onChange={(event) => set('description')(event.target.value)}
          />
        </Field>

        <div className={editing ? 'grid gap-4' : 'grid gap-4 sm:grid-cols-3'}>
          <Field label={t('Category')}>
            <Select
              value={form.categoryId}
              onChange={(event) => set('categoryId')(event.target.value)}
            >
              <option value="">{t('Uncategorised')}</option>
              {(categories.data ?? []).map((category) => (
                <option key={category.id} value={category.id ?? ''}>
                  {category.name}
                </option>
              ))}
            </Select>
          </Field>
          {!editing && (
            <>
              <Field label={t('Stock location')}>
                <Select
                  value={form.locationId}
                  onChange={(event) => set('locationId')(event.target.value)}
                >
                  <option value="">{t('Default location')}</option>
                  {(locations.data ?? []).map((location) => (
                    <option key={location.id} value={location.id ?? ''}>
                      {location.name}
                    </option>
                  ))}
                </Select>
              </Field>
              <Field
                label={t('Opening quantity')}
                hint={t('Recorded as an opening balance in the ledger')}
              >
                <Input
                  type="number"
                  min="0"
                  className="nums"
                  value={form.openingQuantity}
                  onChange={(event) => set('openingQuantity')(event.target.value)}
                />
              </Field>
            </>
          )}
        </div>

        <div className="grid gap-4 sm:grid-cols-2">
          <Field label={t('Unit cost')} error={fieldErrors.unitCost} hint={t('What it costs you. Supplier prices below can override it per supplier.')}>
            <Input
              type="number"
              step="0.01"
              min="0"
              required
              className="nums"
              value={form.unitCost}
              onChange={(event) => set('unitCost')(event.target.value)}
            />
          </Field>
          <Field label={t('Reorder level')} hint={t('0 means do not track')}>
            <Input
              type="number"
              min="0"
              className="nums"
              value={form.reorderLevel}
              onChange={(event) => set('reorderLevel')(event.target.value)}
            />
          </Field>
        </div>

        <div className="grid gap-4 rounded-md border border-rule bg-panel2 p-3 sm:grid-cols-2">
          <Field
            label={t('Retail price')}
            hint={
              canQuote
                ? t('This is the price an order charges.')
                : t('Super admin only — you can read it, not change it')
            }
          >
            <Input
              type="number"
              step="0.01"
              min="0"
              disabled={!canQuote}
              className="nums"
              value={form.retailPrice}
              onChange={(event) => set('retailPrice')(event.target.value)}
            />
          </Field>
          <Field label={t('Wholesale price')}>
            <Input
              type="number"
              step="0.01"
              min="0"
              disabled={!canQuote}
              className="nums"
              value={form.wholesalePrice}
              onChange={(event) => set('wholesalePrice')(event.target.value)}
            />
          </Field>
        </div>

        <Field
          label={t('Picture')}
          hint={t('Optional. JPEG or PNG, up to 10 MB. Customers see it on the item pack pages.')}
        >
          <div className="flex flex-wrap items-center gap-3">
            <input
              type="file"
              accept="image/jpeg,image/png"
              onChange={(event) => void pickImage(event.target.files?.[0])}
              className="w-full rounded-lg border border-rulestrong bg-panel px-3 py-2 text-sm text-ink2 file:mr-3 file:rounded-md file:border-0 file:bg-brandsoft file:px-3 file:py-1 file:font-semibold file:text-brand"
            />
            {uploading && <span className="text-xs text-ink3">{t('Uploading…')}</span>}
            {imageId && !uploading && (
              <div className="flex items-center gap-2">
                <img
                  src={itemImageUrl(imageId)}
                  alt=""
                  className="h-14 w-14 rounded-lg border border-rule object-cover"
                />
                <Button type="button" size="sm" variant="danger" onClick={() => setImageId(null)}>
                  {t('Remove')}
                </Button>
              </div>
            )}
          </div>
        </Field>
        {uploadError != null && <ErrorBanner error={uploadError} />}

        <div>
          <p className="mb-1 text-xs font-semibold tracking-wide text-ink2 uppercase">
            {t('Supplier prices')}
          </p>
          <p className="mb-2 text-xs text-ink3">
            {t('What each supplier charges. A purchase order to that supplier fills in their price automatically.')}
          </p>
          {prices === null && currentPrices.error ? (
            <ErrorBanner error={currentPrices.error} onRetry={() => void currentPrices.refetch()} />
          ) : prices === null ? (
            <Spinner label={t('Loading supplier prices…')} />
          ) : (
            <div className="flex flex-col gap-2">
              {/* The supplier gets a line of its own. Squeezed beside the price and note it was
                  cut to a few characters, and "code — name" is exactly what you need to read to
                  pick the right one. */}
              {rows.map((row, index) => (
                <div
                  key={index}
                  className="flex flex-col gap-2 rounded-md border border-rule bg-panel2/50 p-2"
                >
                  <Select
                    aria-label={t('Supplier')}
                    className="w-full"
                    value={row.supplierId}
                    onChange={(event) => setRow(index, { supplierId: event.target.value })}
                  >
                    <option value="">{t('Select a supplier…')}</option>
                    {(suppliers.data ?? []).map((supplier) => (
                      <option
                        key={supplier.id}
                        value={supplier.id ?? ''}
                        // One price per supplier per item; a second row for the same supplier
                        // would silently overwrite the first.
                        disabled={rows.some(
                          (other, i) => i !== index && other.supplierId === supplier.id,
                        )}
                      >
                        {supplier.code} — {supplier.name}
                      </option>
                    ))}
                  </Select>
                  <div className="flex gap-2">
                    <Input
                      type="number"
                      step="0.01"
                      min="0"
                      className="nums w-32"
                      aria-label={t('Price')}
                      placeholder={t('Price')}
                      value={row.price}
                      onChange={(event) => setRow(index, { price: event.target.value })}
                    />
                    <Input
                      className="min-w-0 flex-1"
                      aria-label={t('Note')}
                      placeholder={t('per case of 24')}
                      value={row.note}
                      onChange={(event) => setRow(index, { note: event.target.value })}
                    />
                    <Button
                      type="button"
                      variant="danger"
                      onClick={() => setPrices(rows.filter((_, i) => i !== index))}
                      aria-label={t('Remove')}
                    >
                      ✕
                    </Button>
                  </div>
                </div>
              ))}
            </div>
          )}
          <Button
            type="button"
            size="sm"
            className="mt-2"
            disabled={prices === null}
            onClick={() => setPrices([...rows, { supplierId: '', price: '', note: '' }])}
          >
            {t('Add a supplier price')}
          </Button>
        </div>

        <ErrorBanner error={saveError} />
        {priceError != null && (
          <div>
            <p className="mb-1 text-xs text-warn">
              {editing
                ? t('The item was saved. Some supplier prices were not — open it again to retry.')
                : t('The item was created. Its supplier prices were not — add them by editing the item.')}
            </p>
            <ErrorBanner error={priceError} />
          </div>
        )}

        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            {t('Cancel')}
          </Button>
          <Button type="submit" variant="primary" disabled={saving || uploading}>
            {editing
              ? saving
                ? t('Saving…')
                : t('Save changes')
              : saving
                ? t('Creating…')
                : t('Create item')}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
