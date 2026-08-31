import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  useCategories,
  useCreateCategory,
  useCreateItem,
  useItems,
  useLocations,
  useStock,
  useSetSupplierPrice,
  useSuppliers,
} from '../api/queries';
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
  PageHeader,
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
  const [creating, setCreating] = useState(false);
  const [creatingCategory, setCreatingCategory] = useState(false);

  const canManageCategories = hasRole('ADMIN');

  const items = useItems(includeInactive);
  const categories = useCategories();

  // Availability is a stock fact, not a catalogue one, so it comes from the ledger and is joined
  // here. Summed across locations: an item held in two warehouses has as much available as the two
  // together, and a per-location figure would need a location picker this table does not want.
  const stock = useStock();
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

  const filtered = (items.data ?? []).filter((item) => {
    if (!search) return true;
    const needle = search.toLowerCase();
    return (
      (item.sku ?? '').toLowerCase().includes(needle) ||
      (item.name ?? '').toLowerCase().includes(needle)
    );
  });

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

      <Card
        title={t('Catalogue')}
        subtitle={t('{{count}} shown', { count: filtered.length })}
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
            <label className="flex items-center gap-2 text-xs text-ink2">
              <input
                type="checkbox"
                checked={includeInactive}
                onChange={(event) => setIncludeInactive(event.target.checked)}
              />
              {t('Show deactivated')}
            </label>
          </>
        }
      >
        {items.isLoading ? (
          <Spinner />
        ) : items.error ? (
          <div className="p-4">
            <ErrorBanner error={items.error} onRetry={() => void items.refetch()} />
          </div>
        ) : filtered.length === 0 ? (
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
                </tr>
              </thead>
              <tbody>
                {filtered.map((item) => {
                  const cost = Number(item.unitCost ?? 0);
                  return (
                    <tr key={item.id} className={item.active ? 'hover:bg-panel2' : 'opacity-60'}>
                      <Td className="font-mono text-xs">{item.sku}</Td>
                      <Td>
                        <span className="text-ink">{item.name}</span>
                        {!item.active && (
                          <Badge tone="danger">
                            <span className="ml-0">{t('deactivated')}</span>
                          </Badge>
                        )}
                      </Td>
                      <Td className="text-xs">{categoryName(item.categoryId)}</Td>
                      <Td align="right">
                        {stock.isLoading ? (
                          <span className="text-ink3">…</span>
                        ) : (
                          <AvailabilityBox available={availableByItem.get(item.id ?? '') ?? 0} />
                        )}
                      </Td>
                      <Td align="right">{cost.toFixed(2)}</Td>
                      {/* An em dash rather than 0.00 — no price set is not a price of nothing. */}
                      <Td align="right" className="text-ink2">
                        {item.retailPrice != null ? Number(item.retailPrice).toFixed(2) : '—'}
                      </Td>
                      <Td align="right" className="text-ink2">
                        {item.wholesalePrice != null ? Number(item.wholesalePrice).toFixed(2) : '—'}
                      </Td>
                      <Td align="right" className="text-ink3">
                        {item.reorderLevel || '—'}
                      </Td>
                    </tr>
                  );
                })}
              </tbody>
            </Table>
          </TableWrap>
        )}
      </Card>

      {creating && <CreateItemModal onClose={() => setCreating(false)} />}
      {creatingCategory && <CreateCategoryModal onClose={() => setCreatingCategory(false)} />}
    </>
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

/**
 * The item form.
 *
 * Three things the client asked for after the demo, each a little more than a field:
 *
 * - **Stock location and opening quantity.** The item is created with a real stock position, so it
 *   is sellable immediately. A non-zero opening balance goes through the ledger as a movement.
 * - **Supplier prices.** Saved after the item exists, because they belong to procurement and need
 *   an item id to point at. If one fails the item still exists, and the form says so rather than
 *   leaving the operator to guess whether to start again.
 * - **Retail and wholesale.** Visible to everyone, editable only by a super admin.
 */
function CreateItemModal({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  const { hasRole } = useAuth();
  const create = useCreateItem();
  const setSupplierPrice = useSetSupplierPrice();
  const categories = useCategories();
  const locations = useLocations();
  const suppliers = useSuppliers();

  const canQuote = hasRole('SUPER_ADMIN');

  const [form, setForm] = useState({
    sku: '',
    name: '',
    description: '',
    categoryId: '',
    locationId: '',
    openingQuantity: '0',
    unitCost: '',
    retailPrice: '',
    wholesalePrice: '',
    reorderLevel: '0',
  });

  const [prices, setPrices] = useState<Array<{ supplierId: string; price: string; note: string }>>(
    [],
  );
  const [priceError, setPriceError] = useState<unknown>(null);

  const fieldErrors = create.error instanceof ApiError ? create.error.fieldErrors : {};
  const update = (key: keyof typeof form) => (value: string) =>
    setForm((previous) => ({ ...previous, [key]: value }));

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setPriceError(null);

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
    });

    try {
      for (const row of prices.filter((entry) => entry.supplierId && entry.price)) {
        await setSupplierPrice.mutateAsync({
          itemId: item.id!,
          body: {
            supplierId: row.supplierId,
            price: Number(row.price),
            note: row.note || undefined,
          },
        });
      }
      onClose();
    } catch (error) {
      setPriceError(error);
    }
  }

  return (
    <Modal title={t('New item')} onClose={onClose} wide>
      <form className="flex flex-col gap-4" onSubmit={(event) => void submit(event)}>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field
            label={t('Item code')}
            hint={t('Stored upper case — slv-001 and SLV-001 are one item')}
            error={fieldErrors.sku}
          >
            <Input
              required
              autoFocus
              value={form.sku}
              onChange={(event) => update('sku')(event.target.value)}
            />
          </Field>
          <Field label={t('Name')} error={fieldErrors.name}>
            <Input
              required
              value={form.name}
              onChange={(event) => update('name')(event.target.value)}
            />
          </Field>
        </div>

        <Field label={t('Description')} hint={t('What it is, in the words a customer would use')}>
          <Input
            value={form.description}
            onChange={(event) => update('description')(event.target.value)}
          />
        </Field>

        <div className="grid gap-4 sm:grid-cols-3">
          <Field label={t('Category')}>
            <Select
              value={form.categoryId}
              onChange={(event) => update('categoryId')(event.target.value)}
            >
              <option value="">{t('Uncategorised')}</option>
              {(categories.data ?? []).map((category) => (
                <option key={category.id} value={category.id ?? ''}>
                  {category.name}
                </option>
              ))}
            </Select>
          </Field>
          <Field label={t('Stock location')}>
            <Select
              value={form.locationId}
              onChange={(event) => update('locationId')(event.target.value)}
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
              onChange={(event) => update('openingQuantity')(event.target.value)}
            />
          </Field>
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
              onChange={(event) => update('unitCost')(event.target.value)}
            />
          </Field>
          <Field label={t('Reorder level')} hint={t('0 means do not track')}>
            <Input
              type="number"
              min="0"
              className="nums"
              value={form.reorderLevel}
              onChange={(event) => update('reorderLevel')(event.target.value)}
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
              onChange={(event) => update('retailPrice')(event.target.value)}
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
              onChange={(event) => update('wholesalePrice')(event.target.value)}
            />
          </Field>
        </div>

        <div>
          <p className="mb-1 text-xs font-semibold tracking-wide text-ink2 uppercase">
            {t('Supplier prices')}
          </p>
          <p className="mb-2 text-xs text-ink3">
            {t('What each supplier charges. A purchase order to that supplier fills in their price automatically.')}
          </p>
          <div className="flex flex-col gap-2">
            {prices.map((row, index) => (
              <div key={index} className="flex gap-2">
                <Select
                  className="flex-1"
                  value={row.supplierId}
                  onChange={(event) => {
                    const next = [...prices];
                    next[index] = { ...row, supplierId: event.target.value };
                    setPrices(next);
                  }}
                >
                  <option value="">{t('Select a supplier…')}</option>
                  {(suppliers.data ?? []).map((supplier) => (
                    <option key={supplier.id} value={supplier.id ?? ''}>
                      {supplier.code} — {supplier.name}
                    </option>
                  ))}
                </Select>
                <Input
                  type="number"
                  step="0.01"
                  min="0"
                  className="nums w-28"
                  title={t('Price')}
                  value={row.price}
                  onChange={(event) => {
                    const next = [...prices];
                    next[index] = { ...row, price: event.target.value };
                    setPrices(next);
                  }}
                />
                <Input
                  className="w-40"
                  placeholder={t('per case of 24')}
                  value={row.note}
                  onChange={(event) => {
                    const next = [...prices];
                    next[index] = { ...row, note: event.target.value };
                    setPrices(next);
                  }}
                />
                <Button
                  type="button"
                  variant="ghost"
                  onClick={() => setPrices(prices.filter((_, i) => i !== index))}
                  aria-label={t('Remove')}
                >
                  ✕
                </Button>
              </div>
            ))}
          </div>
          <Button
            type="button"
            size="sm"
            className="mt-2"
            onClick={() => setPrices([...prices, { supplierId: '', price: '', note: '' }])}
          >
            {t('Add a supplier price')}
          </Button>
        </div>

        <ErrorBanner error={create.error} />
        {priceError != null && (
          <div>
            <p className="mb-1 text-xs text-warn">
              {t('The item was created. Its supplier prices were not — add them by editing the item.')}
            </p>
            <ErrorBanner error={priceError} />
          </div>
        )}

        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            {t('Cancel')}
          </Button>
          <Button type="submit" variant="primary" disabled={create.isPending}>
            {create.isPending ? t('Creating…') : t('Create item')}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
