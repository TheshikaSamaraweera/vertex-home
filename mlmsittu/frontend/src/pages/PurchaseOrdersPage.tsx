import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  useConfirmArrival,
  useCreatePurchaseOrder,
  useItems,
  usePurchaseOrder,
  usePurchaseOrders,
  useReplacePurchaseOrderLines,
  useSendPurchaseOrder,
  useSuppliers,
} from '../api/queries';
import type { PurchaseOrder, Supplier } from '../api/types';
import {
  Badge,
  Button,
  Card,
  EmptyState,
  ErrorBanner,
  Field,
  humanStatus,
  Input,
  Modal,
  PageHeader,
  Select,
  Spinner,
  statusTone,
  Table,
  TableWrap,
  Td,
  Th,
} from '../components/ui';
import { ItemPicker } from '../components/ItemPicker';
import { useAuth } from '../auth/AuthContext';

/**
 * Purchase orders: raise it, edit it, send it, sign for it.
 *
 * The lifecycle is visible in the buttons, and each one is only there in the state it belongs to:
 *
 * | Status | What you can do |
 * |---|---|
 * | draft | edit the lines, send it to the supplier |
 * | sent | confirm it arrived, by typing your name |
 * | arrived | nothing here — it moves to Receiving |
 *
 * Putting goods **into** a store is deliberately not on this screen. Ordering and receiving are
 * separate duties (architecture 8.1), and separating the screens as well as the roles makes that
 * hard to forget.
 */
export function PurchaseOrdersPage() {
  const { t } = useTranslation();
  const { hasRole } = useAuth();
  const canOrder = hasRole('PROCUREMENT_OFFICER');

  const suppliers = useSuppliers(true);
  const orders = usePurchaseOrders();

  const [creatingOrder, setCreatingOrder] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [sending, setSending] = useState<PurchaseOrder | null>(null);
  const [confirming, setConfirming] = useState<PurchaseOrder | null>(null);

  const supplierOf = (id: string | undefined) =>
    (suppliers.data ?? []).find((supplier) => supplier.id === id);

  return (
    <>
      <PageHeader
        title={t('Create order')}
        description={t('Ordering and receiving are separate duties. Once a delivery is confirmed as arrived it moves to the Received orders screen.')}
        actions={
          canOrder && (
            <Button variant="primary" size="sm" onClick={() => setCreatingOrder(true)}>
              {t('New order')}
            </Button>
          )
        }
      />

      <div className="flex flex-col gap-5">
        <Card title={t('Create order')}>
          {orders.isLoading ? (
            <Spinner />
          ) : orders.error ? (
            <div className="p-4">
              <ErrorBanner error={orders.error} onRetry={() => void orders.refetch()} />
            </div>
          ) : (orders.data ?? []).length === 0 ? (
            <EmptyState message={t('No purchase orders yet.')} />
          ) : (
            <TableWrap>
              <Table>
                <thead>
                  <tr>
                    <Th>{t('Number')}</Th>
                    <Th>{t('Supplier')}</Th>
                    <Th>{t('Status')}</Th>
                    <Th align="right">{t('Lines')}</Th>
                    <Th align="right">{t('Total')}</Th>
                    <Th>{t('Progress')}</Th>
                    <Th>{''}</Th>
                  </tr>
                </thead>
                <tbody>
                  {(orders.data ?? []).map((order) => (
                    <tr key={order.id} className="align-top hover:bg-panel2">
                      <Td className="font-mono text-xs">{order.poNumber}</Td>
                      <Td>{supplierOf(order.supplierId)?.name ?? '—'}</Td>
                      <Td>
                        <Badge tone={statusTone(order.status ?? '')}>
                          {humanStatus(order.status)}
                        </Badge>
                      </Td>
                      <Td align="right">{order.lines?.length ?? 0}</Td>
                      <Td align="right">{Number(order.total ?? 0).toFixed(2)}</Td>
                      <Td className="text-xs text-ink3">
                        {/* The audit trail in miniature: where it went, and who signed for it.
                            Both are questions somebody asks a week later. */}
                        {order.sentToEmail && (
                          <p>
                            {t('Sent to')} <span className="text-ink2">{order.sentToEmail}</span>
                          </p>
                        )}
                        {order.arrivalAttestedName && (
                          <p>
                            {t('Signed for by')}{' '}
                            <span className="text-ink2">{order.arrivalAttestedName}</span>
                          </p>
                        )}
                        {!order.sentToEmail && !order.arrivalAttestedName && '—'}
                      </Td>
                      <Td>
                        <div className="flex justify-end gap-1">
                          {canOrder && order.status === 'draft' && (
                            <>
                              <Button size="sm" onClick={() => order.id && setEditingId(order.id)}>
                                {t('Edit')}
                              </Button>
                              <Button size="sm" variant="primary" onClick={() => setSending(order)}>
                                {t('Send')}
                              </Button>
                            </>
                          )}
                          {/* Any signed-in role may sign for a delivery — whoever is at the door
                              when the lorry comes. */}
                          {order.status === 'sent' && (
                            <Button size="sm" variant="primary" onClick={() => setConfirming(order)}>
                              {t('Confirm received')}
                            </Button>
                          )}
                        </div>
                      </Td>
                    </tr>
                  ))}
                </tbody>
              </Table>
            </TableWrap>
          )}
        </Card>
      </div>

      {creatingOrder && <OrderModal onClose={() => setCreatingOrder(false)} />}
      {editingId && <OrderModal editingId={editingId} onClose={() => setEditingId(null)} />}
      {sending && (
        <SendModal
          order={sending}
          supplier={supplierOf(sending.supplierId)}
          onClose={() => setSending(null)}
        />
      )}
      {confirming && (
        <ConfirmArrivalModal order={confirming} onClose={() => setConfirming(null)} />
      )}
    </>
  );
}

/**
 * One modal for raising an order and for editing a draft.
 *
 * Editing replaces the whole set of lines rather than patching them one by one, which mirrors what
 * the server does — and the server refuses it outright once the order has been sent, because the
 * supplier is working from the document we issued.
 */
/** One line as it is being typed. Strings throughout, because that is what an input holds. */
type Line = { itemId: string; quantity: string; unitCost: string };

const EMPTY_LINE: Line = { itemId: '', quantity: '1', unitCost: '' };

function OrderModal({ editingId, onClose }: { editingId?: string; onClose: () => void }) {
  const { t } = useTranslation();
  const create = useCreatePurchaseOrder();
  const replace = useReplacePurchaseOrderLines();
  const suppliers = useSuppliers();
  const items = useItems();
  const existing = usePurchaseOrder(editingId ?? null);

  const [supplierId, setSupplierId] = useState('');
  const [lines, setLines] = useState<Line[]>([]);
  const [loaded, setLoaded] = useState(false);

  // The entry row: one set of fields that never grows, sitting above the table of what has been
  // added. It replaces a form that grew a row per item, where ten items meant thirty inputs on
  // screen at once and the field you wanted was wherever the scroll happened to leave it.
  const [draft, setDraft] = useState<Line>(EMPTY_LINE);

  // Which added line the entry row is currently editing, or null when it is adding a new one.
  // Editing reuses the same fields rather than making the table cells editable: one place where
  // values are typed, and the same validation guarding both.
  const [editingLine, setEditingLine] = useState<number | null>(null);

  // Fill the form from the order once, then leave it alone — refetching mid-edit would throw away
  // whatever the user had typed.
  if (editingId && existing.data && !loaded) {
    const order = existing.data as PurchaseOrder;
    setSupplierId(order.supplierId ?? '');
    setLines(
      (order.lines ?? []).map((line) => ({
        itemId: line.itemId ?? '',
        quantity: String(line.quantityOrdered ?? 1),
        unitCost: line.unitCost != null ? String(line.unitCost) : '',
      })),
    );
    setLoaded(true);
  }

  const usable = lines.filter((line) => line.itemId && Number(line.quantity) > 0);
  const mutation = editingId ? replace : create;

  const itemsById = new Map((items.data ?? []).map((item) => [item.id, item]));

  const draftUsable = Boolean(draft.itemId) && Number(draft.quantity) > 0;

  /** Items already on the order, so the picker cannot offer the same one twice. */
  const alreadyChosen = lines
    .filter((_, index) => index !== editingLine)
    .map((line) => line.itemId);

  function commitDraft() {
    if (!draftUsable) return;
    setLines(
      editingLine === null
        ? [...lines, draft]
        : lines.map((line, index) => (index === editingLine ? draft : line)),
    );
    // Clearing is the point: the next item is typed into an empty row, not into the last one's
    // leftovers, which is how a quantity ends up copied onto the wrong item.
    setDraft(EMPTY_LINE);
    setEditingLine(null);
  }

  /**
   * Enter adds the line instead of submitting the order.
   *
   * The entry fields sit inside the same form as the Create button, so without this Enter would
   * create the order and close the dialog — losing the line the person was in the middle of
   * typing, which is the worst possible response to that keystroke.
   */
  function addOnEnter(event: React.KeyboardEvent) {
    if (event.key !== 'Enter') return;
    event.preventDefault();
    commitDraft();
  }

  function editLine(index: number) {
    const line = lines[index];
    if (!line) return;
    setDraft(line);
    setEditingLine(index);
  }

  function deleteLine(index: number) {
    setLines(lines.filter((_, i) => i !== index));
    if (editingLine === index) {
      // The row being edited has gone; the entry row must not keep pointing at an index that now
      // belongs to a different item.
      setDraft(EMPTY_LINE);
      setEditingLine(null);
    } else if (editingLine !== null && editingLine > index) {
      setEditingLine(editingLine - 1);
    }
  }

  const total = usable.reduce(
    (sum, line) => sum + Number(line.quantity) * Number(line.unitCost || 0),
    0,
  );

  const payloadLines = usable.map((line) => ({
    itemId: line.itemId,
    quantity: Number(line.quantity),
    unitCost: line.unitCost ? Number(line.unitCost) : undefined,
  }));

  return (
    <Modal
      title={editingId ? t('Edit draft order') : t('New order')}
      onClose={onClose}
      wide
    >
      {editingId && existing.isLoading ? (
        <Spinner />
      ) : (
        <form
          className="flex flex-col gap-4"
          onSubmit={(event) => {
            event.preventDefault();
            if (editingId) {
              replace.mutate({ id: editingId, lines: payloadLines }, { onSuccess: onClose });
            } else {
              create.mutate({ supplierId, lines: payloadLines }, { onSuccess: onClose });
            }
          }}
        >
          <Field
            label={t('Supplier')}
            hint={
              editingId
                ? t('The supplier cannot change on an existing order — cancel it and raise another')
                : t('Only active suppliers are listed — a deactivated one is refused server-side too')
            }
          >
            <Select
              required
              disabled={Boolean(editingId)}
              value={supplierId}
              onChange={(event) => setSupplierId(event.target.value)}
            >
              <option value="">{t('Select a supplier…')}</option>
              {(suppliers.data ?? []).map((supplier) => (
                <option key={supplier.id} value={supplier.id ?? ''}>
                  {supplier.code} — {supplier.name}
                </option>
              ))}
            </Select>
          </Field>

          <div>
            <p className="mb-2 text-xs font-semibold tracking-wide text-ink2 uppercase">
              {editingLine === null ? t('Add an item') : t('Edit this item')}
            </p>

            {/* The entry row. Fixed height, always in the same place, whether the order has one
                line or forty. */}
            <div className="flex flex-wrap items-end gap-2 rounded-md border border-rule bg-panel2 p-3">
              <div className="min-w-[16rem] flex-1">
                <label className="mb-1 block text-xs text-ink2">{t('Item')}</label>
                <ItemPicker
                  items={items.data ?? []}
                  value={draft.itemId}
                  exclude={alreadyChosen}
                  onChange={(itemId) => {
                    const chosen = itemsById.get(itemId);
                    setDraft({
                      ...draft,
                      itemId,
                      // Default to the catalogue cost, still editable — the agreed price is what
                      // the supplier will invoice, not whatever the catalogue says today.
                      unitCost: draft.unitCost || String(chosen?.unitCost ?? ''),
                    });
                  }}
                  placeholder={t('Search by name or item code…')}
                />
              </div>
              <div>
                <label className="mb-1 block text-xs text-ink2">{t('Quantity')}</label>
                <Input
                  type="number"
                  min="1"
                  className="nums w-24"
                  value={draft.quantity}
                  onChange={(event) => setDraft({ ...draft, quantity: event.target.value })}
                  onKeyDown={addOnEnter}
                />
              </div>
              <div>
                <label className="mb-1 block text-xs text-ink2">{t('Unit cost')}</label>
                <Input
                  type="number"
                  step="0.01"
                  min="0"
                  className="nums w-32"
                  value={draft.unitCost}
                  onChange={(event) => setDraft({ ...draft, unitCost: event.target.value })}
                  onKeyDown={addOnEnter}
                />
              </div>
              <Button type="button" variant="primary" onClick={commitDraft} disabled={!draftUsable}>
                {editingLine === null ? t('Add') : t('Update')}
              </Button>
              {editingLine !== null && (
                <Button
                  type="button"
                  variant="ghost"
                  onClick={() => {
                    setDraft(EMPTY_LINE);
                    setEditingLine(null);
                  }}
                >
                  {t('Cancel')}
                </Button>
              )}
            </div>

            {/* What is on the order so far. Empty until something is added, rather than showing a
                blank row that looks like it needs filling in. */}
            {lines.length === 0 ? (
              <p className="mt-3 rounded-md border border-dashed border-rule px-3 py-6 text-center text-sm text-ink3">
                {t('No items yet. Choose one above and press Add.')}
              </p>
            ) : (
              <table className="mt-3 w-full text-sm">
                <thead>
                  <tr className="border-b border-rule text-left text-xs text-ink2">
                    <th className="py-2 font-medium">{t('Item')}</th>
                    <th className="py-2 text-right font-medium">{t('Quantity')}</th>
                    <th className="py-2 text-right font-medium">{t('Unit cost')}</th>
                    <th className="py-2 text-right font-medium">{t('Line total')}</th>
                    <th className="py-2" />
                  </tr>
                </thead>
                <tbody>
                  {lines.map((line, index) => {
                    const item = itemsById.get(line.itemId);
                    return (
                      <tr
                        key={line.itemId || index}
                        className={
                          'border-b border-rule ' +
                          (index === editingLine ? 'bg-brandsoft' : '')
                        }
                      >
                        <td className="py-2">
                          <span className="text-ink">{item?.name ?? t('Unknown item')}</span>
                          <span className="ml-2 font-mono text-xs text-ink3">{item?.sku}</span>
                        </td>
                        <td className="nums py-2 text-right">{line.quantity}</td>
                        <td className="nums py-2 text-right">{line.unitCost || '—'}</td>
                        <td className="nums py-2 text-right">
                          {line.unitCost
                            ? (Number(line.quantity) * Number(line.unitCost)).toLocaleString(
                                undefined,
                                { minimumFractionDigits: 2, maximumFractionDigits: 2 },
                              )
                            : '—'}
                        </td>
                        <td className="py-2 text-right whitespace-nowrap">
                          <Button
                            type="button"
                            size="sm"
                            variant="ghost"
                            onClick={() => editLine(index)}
                          >
                            {t('Edit')}
                          </Button>
                          <Button
                            type="button"
                            size="sm"
                            variant="ghost"
                            onClick={() => deleteLine(index)}
                          >
                            {t('Delete')}
                          </Button>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
                <tfoot>
                  <tr>
                    <td className="py-2 text-xs text-ink2" colSpan={3}>
                      {t('{{count}} item(s)', { count: lines.length })}
                    </td>
                    <td className="nums py-2 text-right font-semibold text-ink">
                      {total.toLocaleString(undefined, {
                        minimumFractionDigits: 2,
                        maximumFractionDigits: 2,
                      })}
                    </td>
                    <td />
                  </tr>
                </tfoot>
              </table>
            )}

            {editingLine !== null && (
              <p className="mt-2 text-xs text-ink2">
                {t('Editing a line above. Press Update to keep the change, or Cancel to leave it as it was.')}
              </p>
            )}
          </div>

          <ErrorBanner error={mutation.error} />

          <div className="flex justify-end gap-2">
            <Button type="button" variant="ghost" onClick={onClose}>
              {t('Cancel')}
            </Button>
            <Button
              type="submit"
              variant="primary"
              disabled={mutation.isPending || (!editingId && !supplierId) || usable.length === 0}
            >
              {mutation.isPending
                ? t('Saving…')
                : editingId
                  ? t('Save changes')
                  : t('Create as draft')}
            </Button>
          </div>
        </form>
      )}
    </Modal>
  );
}

/**
 * Sending, with the address shown before you commit.
 *
 * A supplier with no address on file is refused server-side, so the dialog says so up front rather
 * than letting somebody press a button that cannot work.
 */
function SendModal({
  order,
  supplier,
  onClose,
}: {
  order: PurchaseOrder;
  supplier?: Supplier;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const send = useSendPurchaseOrder();

  const address = supplier?.contactEmail || supplier?.email || null;

  return (
    <Modal title={t('Send this order to the supplier?')} onClose={onClose}>
      <div className="flex flex-col gap-4">
        <p className="text-sm text-ink2">
          {t('{{poNumber}} will be emailed and its lines frozen — after this it can no longer be edited.', {
            poNumber: order.poNumber,
          })}
        </p>

        {address ? (
          <div className="rounded-lg border border-rule bg-panel2 p-3">
            <p className="text-xs text-ink3">{t('Going to')}</p>
            <p className="text-sm font-semibold text-ink">{address}</p>
            <p className="mt-1 text-xs text-ink3">
              {supplier?.contactEmail
                ? t('The named contact at {{name}}', { name: supplier?.name })
                : t('The company address for {{name}}', { name: supplier?.name })}
            </p>
          </div>
        ) : (
          <div className="rounded-lg border border-danger/30 bg-dangersoft p-3 text-sm text-danger">
            {t('{{name}} has no email address on file, so this order cannot be sent. Add one on the Suppliers screen first.', {
              name: supplier?.name ?? t('This supplier'),
            })}
          </div>
        )}

        <ErrorBanner error={send.error} />

        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            {t('Cancel')}
          </Button>
          <Button
            type="button"
            variant="primary"
            disabled={send.isPending || !address}
            onClick={() => order.id && send.mutate(order.id, { onSuccess: onClose })}
          >
            {send.isPending ? t('Sending…') : t('Send order')}
          </Button>
        </div>
      </div>
    </Modal>
  );
}

/**
 * Signing for a delivery.
 *
 * You type your own name. The server checks it against your account, so it proves nothing you
 * could not have proved by being signed in — and that is fine, because the purpose is not
 * identification. It is to make confirming a delivery a deliberate act rather than one stray
 * click, and to leave a name against the record afterwards.
 */
function ConfirmArrivalModal({ order, onClose }: { order: PurchaseOrder; onClose: () => void }) {
  const { t } = useTranslation();
  const { user } = useAuth();
  const confirm = useConfirmArrival();
  const [typed, setTyped] = useState('');

  const matches = typed.trim().toLowerCase() === (user?.fullName ?? '').trim().toLowerCase();

  return (
    <Modal title={t('Confirm this delivery arrived')} onClose={onClose}>
      <form
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault();
          if (!order.id) return;
          confirm.mutate({ id: order.id, attestedName: typed }, { onSuccess: onClose });
        }}
      >
        <p className="text-sm text-ink2">
          {t('You are confirming that the goods on {{poNumber}} physically arrived.', {
            poNumber: order.poNumber,
          })}
        </p>
        <p className="text-xs text-ink3">
          {t('This does not change any stock figure. The order moves to the Received orders screen, where the goods are assigned to a store — that is what raises stock.')}
        </p>

        <Field
          label={t('Type your name to confirm')}
          hint={t('Exactly as it appears on your account: {{name}}', { name: user?.fullName ?? '' })}
        >
          <Input
            required
            autoFocus
            autoComplete="off"
            value={typed}
            onChange={(event) => setTyped(event.target.value)}
            placeholder={user?.fullName ?? ''}
          />
        </Field>

        <ErrorBanner error={confirm.error} />

        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            {t('Cancel')}
          </Button>
          <Button type="submit" variant="primary" disabled={confirm.isPending || !matches}>
            {confirm.isPending ? t('Confirming…') : t('Confirm received')}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
