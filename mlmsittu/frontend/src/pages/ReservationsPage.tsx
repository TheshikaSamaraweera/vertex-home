import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  useCreateReservation,
  useExpireReservations,
  useItemSets,
  useItems,
  useReleaseReservation,
  useReservations,
} from '../api/queries';
import {
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
  statusTone,
  Table,
  TableWrap,
  Td,
  Th,
} from '../components/ui';
import { useAuth } from '../auth/AuthContext';

/**
 * Reservations — the authoritative claim on stock.
 *
 * Lines shown here are the *expanded* components, not the sets that were asked for. That is what
 * the backend stores, because release has to give back exactly what reserve took, and after
 * expansion the only thing taken is a quantity of a component item.
 */
export function ReservationsPage() {
  const { t } = useTranslation();
  const { hasRole } = useAuth();
  const canWrite = hasRole('INVENTORY_CLERK');

  const [activeOnly, setActiveOnly] = useState(false);
  const [creating, setCreating] = useState(false);

  const reservations = useReservations(activeOnly);
  const release = useReleaseReservation();
  const expire = useExpireReservations();
  const items = useItems();
  const sets = useItemSets();

  const itemLabel = (id: string | undefined) => {
    const item = (items.data ?? []).find((candidate) => candidate.id === id);
    return item ? `${item.sku}` : (id ?? '').slice(0, 8);
  };

  return (
    <>
      <PageHeader
        title={t('Reservations')}
        description={t('Reserving holds stock without removing it. On hand only falls at fulfilment.')}
        actions={
          <>
            <Button size="sm" onClick={() => expire.mutate(undefined)} disabled={expire.isPending}>
              {t('Run expiry sweep')}
            </Button>
            {canWrite && (
              <Button variant="primary" size="sm" onClick={() => setCreating(true)}>
                {t('New reservation')}
              </Button>
            )}
          </>
        }
      />

      {(release.error || expire.error) && (
        <div className="mb-4">
          <ErrorBanner error={release.error ?? expire.error} />
        </div>
      )}

      <Card
        title={t('Reservations')}
        actions={
          <label className="flex items-center gap-2 text-xs text-ink2">
            <input
              type="checkbox"
              checked={activeOnly}
              onChange={(event) => setActiveOnly(event.target.checked)}
            />
            {t('Active only')}
          </label>
        }
      >
        {reservations.isLoading ? (
          <Spinner />
        ) : reservations.error ? (
          <div className="p-4">
            <ErrorBanner error={reservations.error} onRetry={() => void reservations.refetch()} />
          </div>
        ) : (reservations.data ?? []).length === 0 ? (
          <EmptyState
            message={t('No reservations.')}
            hint={t('Reserve a set to see components held across the board.')}
          />
        ) : (
          <TableWrap>
            <Table>
              <thead>
                <tr>
                  <Th>{t('Status')}</Th>
                  <Th>{t('For')}</Th>
                  <Th>{t('Components held')}</Th>
                  <Th>{t('Created')}</Th>
                  <Th>{t('Expires')}</Th>
                  <Th>{''}</Th>
                </tr>
              </thead>
              <tbody>
                {(reservations.data ?? []).map((reservation) => (
                  <tr key={reservation.id} className="hover:bg-panel2">
                    <Td>
                      <Badge tone={statusTone(reservation.status ?? '')}>{reservation.status}</Badge>
                    </Td>
                    <Td className="text-xs">{reservation.referenceType ?? t('standalone')}</Td>
                    <Td>
                      <div className="nums flex flex-wrap gap-x-3 gap-y-1 text-xs">
                        {(reservation.lines ?? []).map((line) => (
                          <span key={line.itemId} className="whitespace-nowrap">
                            <span className="font-mono text-ink">{itemLabel(line.itemId)}</span>
                            <span className="text-ink3"> ×{line.quantity}</span>
                          </span>
                        ))}
                      </div>
                    </Td>
                    <Td className="text-xs whitespace-nowrap">{formatWhen(reservation.createdAt)}</Td>
                    <Td className="text-xs whitespace-nowrap">
                      {reservation.expiresAt ? formatWhen(reservation.expiresAt) : t('never')}
                    </Td>
                    <Td>
                      {canWrite && reservation.status === 'active' && (
                        <div className="flex justify-end">
                          <Button
                            size="sm"
                            variant="ghost"
                            disabled={release.isPending}
                            onClick={() =>
                              reservation.id &&
                              release.mutate({ id: reservation.id, reason: 'released from UI' })
                            }
                          >
                            {t('Release')}
                          </Button>
                        </div>
                      )}
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          </TableWrap>
        )}
      </Card>

      {creating && (
        <CreateReservationModal
          onClose={() => setCreating(false)}
          items={items.data ?? []}
          sets={sets.data ?? []}
        />
      )}
    </>
  );
}

function CreateReservationModal({
  onClose,
  items,
  sets,
}: {
  onClose: () => void;
  items: Array<{ id?: string; sku?: string; name?: string }>;
  sets: Array<{ id?: string; code?: string; name?: string }>;
}) {
  const { t } = useTranslation();
  const create = useCreateReservation();

  const [lines, setLines] = useState<Array<{ kind: 'set' | 'item'; id: string; quantity: string }>>(
    [{ kind: 'set', id: '', quantity: '1' }],
  );
  const [ttlMinutes, setTtlMinutes] = useState('');

  const usable = lines.filter((line) => line.id);

  return (
    <Modal title={t('New reservation')} onClose={onClose} wide>
      <form
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault();
          create.mutate(
            {
              lines: usable.map((line) => ({
                setId: line.kind === 'set' ? line.id : undefined,
                itemId: line.kind === 'item' ? line.id : undefined,
                quantity: Number(line.quantity),
              })),
              referenceType: 'manual',
              ttlMinutes: ttlMinutes ? Number(ttlMinutes) : undefined,
            },
            { onSuccess: onClose },
          );
        }}
      >
        <div className="flex flex-col gap-2">
          {lines.map((line, index) => (
            <div key={index} className="flex gap-2">
              <Select
                className="w-28"
                value={line.kind}
                onChange={(event) => {
                  const next = [...lines];
                  next[index] = {
                    ...line,
                    kind: event.target.value as 'set' | 'item',
                    id: '',
                  };
                  setLines(next);
                }}
              >
                <option value="set">{t('Set')}</option>
                <option value="item">{t('Item')}</option>
              </Select>

              <Select
                className="flex-1"
                value={line.id}
                onChange={(event) => {
                  const next = [...lines];
                  next[index] = { ...line, id: event.target.value };
                  setLines(next);
                }}
              >
                <option value="">{t('Select…')}</option>
                {line.kind === 'set'
                  ? sets.map((set) => (
                      <option key={set.id} value={set.id ?? ''}>
                        {set.code} — {set.name}
                      </option>
                    ))
                  : items.map((item) => (
                      <option key={item.id} value={item.id ?? ''}>
                        {item.sku} — {item.name}
                      </option>
                    ))}
              </Select>

              <Input
                type="number"
                min="1"
                className="nums w-24"
                value={line.quantity}
                onChange={(event) => {
                  const next = [...lines];
                  next[index] = { ...line, quantity: event.target.value };
                  setLines(next);
                }}
              />
              <Button
                type="button"
                variant="ghost"
                onClick={() => setLines(lines.filter((_, i) => i !== index))}
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
          onClick={() => setLines([...lines, { kind: 'set', id: '', quantity: '1' }])}
        >
          {t('Add line')}
        </Button>

        <Field
          label={t('Hold for (minutes)')}
          hint={t('Leave empty to hold until released. A TTL stops an abandoned order holding stock forever.')}
        >
          <Input
            type="number"
            min="1"
            className="nums"
            placeholder={t('no expiry')}
            value={ttlMinutes}
            onChange={(event) => setTtlMinutes(event.target.value)}
          />
        </Field>

        <ErrorBanner error={create.error} />

        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            {t('Cancel')}
          </Button>
          <Button
            type="submit"
            variant="primary"
            disabled={create.isPending || usable.length === 0}
          >
            {create.isPending ? t('Reserving…') : t('Reserve')}
          </Button>
        </div>
      </form>
    </Modal>
  );
}

function formatWhen(iso: string | undefined): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleString(undefined, { dateStyle: 'short', timeStyle: 'short' });
}
