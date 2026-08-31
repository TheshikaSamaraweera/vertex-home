import { useState } from 'react';
import { ItemPicker } from '../components/ItemPicker';
import { useTranslation } from 'react-i18next';
import {
  useCreateItemSet,
  useDeleteItemSet,
  useItems,
  useSetAvailability,
  useUpdateItemSet,
} from '../api/queries';
import type { SetAvailability } from '../api/types';
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
  Spinner,
} from '../components/ui';
import { useAuth } from '../auth/AuthContext';

/**
 * Item sets, shown through availability rather than composition.
 *
 * Composition is static and dull; what a user needs to know is how many they could actually
 * assemble and whether that number is trustworthy. Architecture 4.2 calls availability advisory
 * because two sets sharing a component can each report the same units — so contention is surfaced
 * on the card, not buried in a tooltip.
 */
export function ItemSetsPage() {
  const { t } = useTranslation();
  const { hasRole } = useAuth();
  const canWrite = hasRole('INVENTORY_CLERK');

  const availability = useSetAvailability();
  const remove = useDeleteItemSet();
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<SetAvailability | null>(null);
  const [confirmingDelete, setConfirmingDelete] = useState<SetAvailability | null>(null);

  const contended = (availability.data ?? []).filter((set) => set.contended);

  return (
    <>
      <PageHeader
        title={t('Item sets')}
        description={t('A set consumes nothing until it is released. Availability is computed from the scarcest component.')}
        actions={
          canWrite && (
            <Button variant="primary" size="sm" onClick={() => setCreating(true)}>
              {t('New set')}
            </Button>
          )
        }
      />

      {contended.length > 1 && (
        <div className="mb-5 rounded border border-warn bg-warnsoft px-4 py-3 text-sm text-ink">
          <p className="font-semibold text-warn">{t('These sets compete for the same stock')}</p>
          <p className="mt-1 text-xs text-ink2">
            {t(
              'Each figure below is true on its own. Together they are not — the shared components can only be sold once. Only a reservation settles it.',
            )}
          </p>
        </div>
      )}

      {availability.isLoading ? (
        <Spinner />
      ) : availability.error ? (
        <ErrorBanner error={availability.error} onRetry={() => void availability.refetch()} />
      ) : (availability.data ?? []).length === 0 ? (
        <Card>
          <EmptyState message={t('No item sets yet.')} />
        </Card>
      ) : (
        <div className="grid gap-4 lg:grid-cols-2 xl:grid-cols-3">
          {(availability.data ?? []).map((set) => (
            <SetCard
              key={set.setId}
              set={set}
              canWrite={canWrite}
              onEdit={() => setEditing(set)}
              onDelete={() => setConfirmingDelete(set)}
            />
          ))}
        </div>
      )}

      {remove.error != null && (
        <div className="mt-5">
          <ErrorBanner error={remove.error} />
        </div>
      )}

      {creating && <SetModal onClose={() => setCreating(false)} />}
      {editing && <SetModal existing={editing} onClose={() => setEditing(null)} />}
      {confirmingDelete && (
        <ConfirmDeleteSet
          set={confirmingDelete}
          pending={remove.isPending}
          onCancel={() => setConfirmingDelete(null)}
          onConfirm={() => {
            if (!confirmingDelete.setId) return;
            remove.mutate(confirmingDelete.setId, {
              onSuccess: () => setConfirmingDelete(null),
              // A set that has been sold cannot be deleted. The dialog closes anyway so the
              // banner explaining why is not hidden behind it.
              onError: () => setConfirmingDelete(null),
            });
          }}
        />
      )}
    </>
  );
}

function SetCard({
  set,
  canWrite,
  onEdit,
  onDelete,
}: {
  set: SetAvailability;
  canWrite: boolean;
  onEdit: () => void;
  onDelete: () => void;
}) {
  const { t } = useTranslation();
  const available = set.availableSets ?? 0;

  return (
    <Card
      title={set.name ?? ''}
      subtitle={set.code ?? ''}
      actions={
        canWrite && (
          <>
            <Button size="sm" onClick={onEdit}>
              {t('Edit')}
            </Button>
            <Button size="sm" variant="danger" onClick={onDelete}>
              {t('Delete')}
            </Button>
          </>
        )
      }
    >
      <div className="flex items-end justify-between gap-3 px-4 pt-4">
        <div>
          <p className="text-[10px] font-semibold tracking-wider text-ink3 uppercase">
            {t('Sets available')}
          </p>
          <p
            className={`nums text-3xl font-bold ${available === 0 ? 'text-danger' : 'text-ink'}`}
          >
            {available}
          </p>
        </div>
        <div className="flex items-end gap-3">
          {/* The price the set sells for. It was stored and never shown, so nobody could tell
              whether it had saved — and editing quietly reset it to zero. */}
          <div className="text-right">
            <p className="text-[10px] font-semibold tracking-wider text-ink3 uppercase">
              {t('Set price')}
            </p>
            <p className="nums text-lg font-semibold text-ink">
              {set.setPrice != null ? money(set.setPrice) : '—'}
            </p>
          </div>
          {set.contended ? (
            <Badge tone="warn">{t('shared components')}</Badge>
          ) : (
            <Badge tone="ok">{t('exclusive')}</Badge>
          )}
        </div>
      </div>

      {/* One row per component: what it is, how many the set needs, and how much there is.
          The per-component "supports N sets" figure is gone — it invited the reader to add four
          numbers that cannot be added, when the only total that means anything is the one at the
          top of the card. */}
      <ul className="mt-3 divide-y divide-rule border-t border-rule">
        <li className="flex items-center justify-between gap-3 bg-panel2 px-4 py-1.5">
          <span className="font-mono text-[9.5px] tracking-wider text-ink3 uppercase">
            {t('Component')}
          </span>
          <span className="flex items-center gap-3 font-mono text-[9.5px] tracking-wider text-ink3 uppercase">
            <span className="w-14 text-right">{t('Per set')}</span>
            <span className="w-16 text-right">{t('Available')}</span>
          </span>
        </li>

        {(set.components ?? []).map((component) => {
          const limiting = component.itemId === set.limitingItemId;
          return (
            <li
              key={component.itemId}
              className={
                'flex items-center justify-between gap-3 px-4 py-2 text-xs ' +
                (limiting ? 'bg-brandsoft' : '')
              }
            >
              <div className="min-w-0">
                {/* Name first. A SKU tells whoever is looking at a short set nothing about what
                    they actually need to order. */}
                <p className="truncate text-ink">{component.name ?? component.sku}</p>
                <p className="font-mono text-[10px] text-ink3">
                  {component.sku}
                  {component.contended && <span className="ml-2 text-warn">{t('shared')}</span>}
                </p>
              </div>
              <div className="flex flex-none items-center gap-3">
                <span className="nums w-14 text-right text-ink2">×{component.perSet}</span>
                <span className="w-16 text-right">
                  <AvailabilityBox available={component.available} size="sm" />
                </span>
              </div>
            </li>
          );
        })}
      </ul>

      {/* The binding constraint is named, because "why is this number so low" is the question
          every user asks next. */}
      {set.limitingItemId && available > 0 && (
        <p className="border-t border-rule px-4 py-2 text-[11px] text-ink3">
          {t('Limited by')}{' '}
          <span className="text-ink2">
            {(() => {
              const limiting = (set.components ?? []).find(
                (c) => c.itemId === set.limitingItemId,
              );
              return limiting?.name ?? limiting?.sku;
            })()}
          </span>
        </p>
      )}
    </Card>
  );
}

/** Create and edit are the same form; only the code field and the verb differ. */
function SetModal({ existing, onClose }: { existing?: SetAvailability; onClose: () => void }) {
  const { t } = useTranslation();
  const items = useItems();
  const create = useCreateItemSet();
  const update = useUpdateItemSet();
  const mutation = existing ? update : create;

  const [code, setCode] = useState(existing?.code ?? '');
  const [name, setName] = useState(existing?.name ?? '');
  // Prefilled when editing. Starting empty silently wiped the price on every edit: the field
  // looked untouched, but the form posts Number('') === 0 and destroyed the value anyway.
  const [setPrice, setSetPrice] = useState(
    existing?.setPrice != null ? String(existing.setPrice) : '',
  );
  const [components, setComponents] = useState<Array<{ itemId: string; quantity: string }>>(
    // Editing starts from what the set already contains, which the availability feed carries.
    existing
      ? (existing.components ?? []).map((component) => ({
          itemId: component.itemId ?? '',
          quantity: String(component.perSet ?? 1),
        }))
      : [{ itemId: '', quantity: '1' }],
  );

  const usable = components.filter((component) => component.itemId);

  const body = {
    name,
    setPrice: Number(setPrice),
    components: usable.map((component) => ({
      itemId: component.itemId,
      quantity: Number(component.quantity),
    })),
  };

  return (
    <Modal title={existing ? t('Edit item set') : t('New item set')} onClose={onClose} wide>
      <form
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault();
          if (existing?.setId) {
            update.mutate({ id: existing.setId, body }, { onSuccess: onClose });
          } else {
            create.mutate({ code, ...body }, { onSuccess: onClose });
          }
        }}
      >
        <div className="grid gap-4 sm:grid-cols-3">
          <Field
            label={t('Code')}
            hint={existing ? t('The code cannot change — order lines point at it') : undefined}
          >
            <Input
              required
              autoFocus={!existing}
              disabled={Boolean(existing)}
              value={code}
              onChange={(event) => setCode(event.target.value)}
              placeholder="SET-D"
            />
          </Field>
          <Field label={t('Name')}>
            <Input required value={name} onChange={(event) => setName(event.target.value)} />
          </Field>
          <Field label={t('Set price')} hint={t('Independent of component total')}>
            <Input
              type="number"
              step="0.01"
              min="0"
              required
              className="nums"
              value={setPrice}
              onChange={(event) => setSetPrice(event.target.value)}
            />
          </Field>
        </div>

        <div>
          <p className="mb-1 text-xs font-semibold tracking-wide text-ink2 uppercase">
            {t('Items in this set')}
          </p>
          <p className="mb-2 text-xs text-ink3">
            {t('Type to search the catalogue, or click the field to browse it. Quantity is how many of that item one set contains.')}
          </p>

          {components.length > 0 && (
            <div className="mb-1 flex gap-2 px-1">
              <span className="flex-1 font-mono text-[9.5px] tracking-wider text-ink3 uppercase">
                {t('Item')}
              </span>
              <span className="w-20 font-mono text-[9.5px] tracking-wider text-ink3 uppercase">
                {t('Qty per set')}
              </span>
              <span className="w-9" />
            </div>
          )}

          <div className="flex flex-col gap-2">
            {components.map((component, index) => (
              <div key={index} className="flex gap-2">
                <div className="flex-1">
                  <ItemPicker
                    items={items.data ?? []}
                    value={component.itemId}
                    // An item cannot appear twice in one set — the server refuses it, so the
                    // picker does not offer it either.
                    exclude={components
                      .filter((_, i) => i !== index)
                      .map((other) => other.itemId)
                      .filter(Boolean)}
                    onChange={(itemId) => {
                      const next = [...components];
                      next[index] = { ...component, itemId };
                      setComponents(next);
                    }}
                  />
                </div>
                <Input
                  type="number"
                  min="1"
                  className="nums w-20"
                  aria-label={t('Quantity per set')}
                  value={component.quantity}
                  onChange={(event) => {
                    const next = [...components];
                    next[index] = { ...component, quantity: event.target.value };
                    setComponents(next);
                  }}
                />
                <Button
                  type="button"
                  variant="ghost"
                  onClick={() => setComponents(components.filter((_, i) => i !== index))}
                  aria-label={t('Remove this item')}
                >
                  ✕
                </Button>
              </div>
            ))}
          </div>

          {components.length === 0 && (
            <p className="rounded-md border border-dashed border-rule px-3 py-4 text-center text-xs text-ink3">
              {t('No items yet. A set needs at least one.')}
            </p>
          )}

          <Button
            type="button"
            size="sm"
            className="mt-2"
            onClick={() => setComponents([...components, { itemId: '', quantity: '1' }])}
          >
            {t('Add item to set')}
          </Button>
        </div>

        <ErrorBanner error={mutation.error} />

        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            {t('Cancel')}
          </Button>
          <Button
            type="submit"
            variant="primary"
            disabled={mutation.isPending || usable.length === 0}
          >
            {mutation.isPending
              ? t('Saving…')
              : existing
                ? t('Save changes')
                : t('Create set')}
          </Button>
        </div>
      </form>
    </Modal>
  );
}

/**
 * Deleting is destructive and the button sits next to Edit, so it asks first.
 *
 * <p>The dialog says what will happen rather than "are you sure": a set that has been sold cannot
 * be deleted at all, and knowing that before clicking is more use than a second click.
 */
function ConfirmDeleteSet({
  set,
  pending,
  onCancel,
  onConfirm,
}: {
  set: SetAvailability;
  pending: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const { t } = useTranslation();
  return (
    <Modal title={t('Delete this set?')} onClose={onCancel}>
      <div className="flex flex-col gap-4">
        <p className="text-sm text-ink2">
          {t('“{{name}}” ({{code}}) will be removed, along with its list of components. The items themselves are untouched.', {
            name: set.name,
            code: set.code,
          })}
        </p>
        <p className="text-xs text-ink3">
          {t('If the set has ever been sold it cannot be deleted — an order line points at it. Deactivate it instead, which stops new orders and leaves the history intact.')}
        </p>
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onCancel}>
            {t('Cancel')}
          </Button>
          <Button type="button" variant="danger" disabled={pending} onClick={onConfirm}>
            {pending ? t('Deleting…') : t('Delete set')}
          </Button>
        </div>
      </div>
    </Modal>
  );
}
