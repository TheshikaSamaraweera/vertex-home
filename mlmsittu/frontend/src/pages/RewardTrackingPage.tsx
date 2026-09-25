import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router-dom';
import {
  itemImageUrl,
  itemSetImageUrl,
  useChangeRewardStage,
  useCompleteReward,
  useLocations,
  useSetReceiveMethod,
  useTrackedRewards,
  type ReceiveMethodBody,
} from '../api/queries';
import type { RewardEntitlement } from '../api/types';
import { Icon, type IconName } from '../components/icons';
import { BoardingPassModal, PackStepper, type BoardingPassData } from '../components/PackTracking';
import {
  Badge,
  Button,
  EmptyState,
  ErrorBanner,
  Field,
  Input,
  Modal,
  PageHeader,
  PasswordInput,
  Select,
  Spinner,
} from '../components/ui';
import { methodLabel, stageTitle, stageTone } from '../lib/packTracking';

const when = (iso?: string | null) =>
  iso ? new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' }) : '—';

const initials = (name?: string | null) =>
  (name ?? '?')
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]!.toUpperCase())
    .join('');

type StageFilter = '' | 'awaiting_method' | 'preparing' | 'dispatched' | 'completed';

const STAGE_TILES: Array<{ stage: StageFilter; label: string; icon: IconName; tile: string }> = [
  { stage: 'awaiting_method', label: 'Waiting for method', icon: 'clipboard', tile: 'from-[#f6b35c] to-[#e07a2b]' },
  { stage: 'preparing', label: 'Preparing', icon: 'package', tile: 'from-[#7c8cff] to-[#4f5bd5]' },
  { stage: 'dispatched', label: 'Pickup / delivery', icon: 'truck', tile: 'from-[#38b2a3] to-[#0b7a6e]' },
  { stage: 'completed', label: 'Completed', icon: 'check', tile: 'from-[#34d399] to-[#059669]' },
];

/** A boarding pass from the office's view of a pack. */
function passFromAdmin(row: RewardEntitlement): BoardingPassData {
  const tracking = row.tracking!;
  return {
    trackingNumber: tracking.trackingNumber ?? '',
    packCode: row.itemSetCode,
    packName: row.itemSetName,
    items: row.packItems ?? [],
    customerName: row.distributorName,
    businessId: row.businessId,
    customerMobile: row.distributorMobile,
    customerEmail: row.distributorEmail,
    method: tracking.receiveMethod,
    pickupLocationName: tracking.pickupLocationName,
    deliveryAddress: tracking.deliveryAddress,
    deliveryContact: tracking.deliveryContact,
    warehouseName: tracking.handoverLocationName,
    warehouseAddress: tracking.handoverLocationAddress,
    issuedAt: row.issuedAt,
    completedAt: tracking.completedAt,
    completedByName: tracking.completedByName,
  };
}

/**
 * Issued item packs, tracked until they are in the customer's hands.
 *
 * A pack lands here the moment it is issued on the Reward packs page. From here the office sets
 * or corrects how it will be received, moves it through the stages — each move is shown to the
 * customer straight away — and completes it at a named warehouse, which prints the boarding pass
 * and closes the customer's business account.
 */
export function RewardTrackingPage() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const selectedId = params.get('id');
  const [stage, setStage] = useState<StageFilter>('');
  const [search, setSearch] = useState('');

  // One list for everything: the tiles count it, the filters narrow it.
  const tracked = useTrackedRewards();
  const all = tracked.data ?? [];
  const counts = useMemo(() => {
    const map = new Map<string, number>();
    for (const row of all) {
      const key = row.tracking?.stage ?? '';
      map.set(key, (map.get(key) ?? 0) + 1);
    }
    return map;
  }, [all]);

  const needle = search.trim().toLowerCase();
  const rows = all.filter(
    (row) =>
      (!stage || row.tracking?.stage === stage) &&
      (!needle ||
        [row.distributorName, row.businessId, row.tracking?.trackingNumber, row.distributorMobile, row.itemSetName].some(
          (field) => (field ?? '').toLowerCase().includes(needle),
        )),
  );
  const selected = all.find((row) => row.id === selectedId) ?? null;

  const select = (id: string) => setParams({ id }, { replace: true });

  return (
    <>
      <PageHeader
        title={t('Pack tracking')}
        description={t('Every issued item pack, from the moment it leaves the store until the customer has it. Each change shows on the customer’s dashboard straight away.')}
      />

      <div className="mb-5 grid grid-cols-2 gap-4 lg:grid-cols-4">
        {STAGE_TILES.map((tile) => (
          <button
            key={tile.stage}
            type="button"
            aria-pressed={stage === tile.stage}
            onClick={() => setStage(stage === tile.stage ? '' : tile.stage)}
            className={
              'flex items-center gap-3 rounded-2xl border bg-panel p-4 text-left shadow-card transition-all hover:-translate-y-0.5 ' +
              (stage === tile.stage ? 'border-brand ring-2 ring-brand/25' : 'border-rule')
            }
          >
            <span className={`flex h-11 w-11 flex-none items-center justify-center rounded-xl bg-linear-to-br text-white shadow-sm ${tile.tile}`}>
              <Icon name={tile.icon} className="h-5 w-5" />
            </span>
            <span className="min-w-0">
              <span className="block text-xs font-medium text-ink3">{t(tile.label)}</span>
              <span className="nums block text-2xl leading-tight font-extrabold text-ink">{counts.get(tile.stage) ?? 0}</span>
            </span>
          </button>
        ))}
      </div>

      <div className="grid gap-5 xl:grid-cols-[380px_minmax(0,1fr)]">
        <section className="self-start rounded-2xl border border-rule bg-panel shadow-card">
          <header className="border-b border-rule px-5 py-4">
            <h2 className="text-[15px] font-semibold text-ink">{t('Issued packs')}</h2>
            <p className="text-[13px] text-ink3">
              {stage ? t(STAGE_TILES.find((tile) => tile.stage === stage)!.label) : t('All stages')} ·{' '}
              {t('{{count}} shown', { count: rows.length })}
            </p>
          </header>
          <div className="border-b border-rule p-3">
            <Input
              type="search"
              aria-label={t('Search packs')}
              placeholder={t('Name, business ID or tracking number…')}
              value={search}
              onChange={(event) => setSearch(event.target.value)}
            />
          </div>
          {tracked.isLoading ? (
            <Spinner />
          ) : tracked.error ? (
            <div className="p-4">
              <ErrorBanner error={tracked.error} onRetry={() => void tracked.refetch()} />
            </div>
          ) : rows.length === 0 ? (
            <EmptyState
              message={all.length ? t('Nothing matches these filters.') : t('No packs have been issued yet.')}
              hint={all.length ? undefined : t('A pack appears here as soon as it is issued on the Reward packs page.')}
            />
          ) : (
            <ul className="max-h-[70vh] divide-y divide-rule overflow-y-auto">
              {rows.map((row) => (
                <li key={row.id}>
                  <button
                    type="button"
                    onClick={() => row.id && select(row.id)}
                    className={
                      'relative flex w-full items-start gap-3 px-4 py-3.5 text-left transition-colors ' +
                      (row.id === selectedId ? 'bg-brandsoft/60' : 'hover:bg-panel2')
                    }
                  >
                    {row.id === selectedId && <span aria-hidden className="absolute inset-y-0 left-0 w-1 bg-brand" />}
                    <span className="flex h-10 w-10 flex-none items-center justify-center rounded-full bg-linear-to-br from-[#f6b35c] to-[#e07a2b] text-xs font-bold text-white">
                      {initials(row.distributorName)}
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="flex items-baseline justify-between gap-2">
                        <span className="truncate text-sm font-semibold text-ink">{row.distributorName}</span>
                        <span className="flex-none font-mono text-[11px] text-ink3">{row.businessId}</span>
                      </span>
                      <span className="block font-mono text-xs font-semibold text-brand">{row.tracking?.trackingNumber}</span>
                      <span className="mt-1 flex flex-wrap items-center gap-1.5">
                        <Badge tone={stageTone(row.tracking?.stage)}>
                          {t(stageTitle(row.tracking?.stage, row.tracking?.receiveMethod))}
                        </Badge>
                        <span className="truncate text-xs text-ink3">{row.itemSetName}</span>
                      </span>
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </section>

        {selected ? (
          <TrackingDetail key={selected.id} row={selected} />
        ) : (
          <section className="flex min-h-[320px] flex-col items-center justify-center rounded-2xl border border-rule bg-panel p-8 text-center shadow-card">
            <span className="mb-3 flex h-14 w-14 items-center justify-center rounded-2xl bg-brandsoft text-brand">
              <Icon name="truck" className="h-7 w-7" />
            </span>
            <p className="font-semibold text-ink">{t('Select a pack to track')}</p>
            <p className="mt-1 max-w-sm text-sm text-ink3">
              {t('See where it is, set how the customer receives it, and move it through the stages.')}
            </p>
          </section>
        )}
      </div>
    </>
  );
}

function TrackingDetail({ row }: { row: RewardEntitlement }) {
  const { t } = useTranslation();
  const tracking = row.tracking!;
  const stage = tracking.stage;
  const method = tracking.receiveMethod;
  const completed = stage === 'completed';

  const [editingMethod, setEditingMethod] = useState(false);
  const [completing, setCompleting] = useState(false);
  const [passOpen, setPassOpen] = useState(false);
  const changeStage = useChangeRewardStage();

  const move = (to: 'preparing' | 'dispatched') => row.id && changeStage.mutate({ id: row.id, stage: to });

  return (
    <div className="flex flex-col gap-5">
      {/* Header: who, what, and where it is. */}
      <section className="overflow-hidden rounded-2xl border border-rule bg-panel shadow-card">
        <div className="flex flex-col gap-4 p-5 sm:flex-row sm:items-start">
          <div className="h-28 w-full flex-none overflow-hidden rounded-xl bg-panel2 sm:w-40">
            {row.itemSetImageId ? (
              <img src={itemSetImageUrl(row.itemSetImageId)} alt="" className="h-full w-full object-cover" />
            ) : (
              <div className="flex h-full items-center justify-center text-ink3">
                <Icon name="gift" className="h-10 w-10" />
              </div>
            )}
          </div>
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-2">
              <h2 className="text-lg font-bold text-ink">{row.distributorName}</h2>
              <Badge tone={stageTone(stage)}>{t(stageTitle(stage, method))}</Badge>
            </div>
            <p className="text-sm text-ink3">
              <span className="font-mono font-semibold text-brand">{row.businessId}</span> · {row.distributorMobile ?? '—'} ·{' '}
              {row.distributorEmail ?? '—'}
            </p>
            <p className="mt-2 text-sm text-ink2">
              <span className="font-semibold text-ink">{row.itemSetName}</span>{' '}
              <span className="font-mono text-xs text-ink3">{row.itemSetCode}</span>
            </p>
            <p className="text-xs text-ink3">
              {t('Issued {{when}} from {{store}}', { when: when(row.issuedAt), store: row.issuedFromLocationName ?? '—' })}
            </p>
          </div>
          <div className="rounded-xl bg-panel2 px-4 py-2.5 text-right">
            <p className="text-[10px] font-bold tracking-[0.14em] text-ink3 uppercase">{t('Tracking number')}</p>
            <p className="font-mono text-base font-bold text-ink">{tracking.trackingNumber}</p>
          </div>
        </div>

        <div className="border-t border-rule bg-panel2/50 px-5 py-5">
          <PackStepper stage={stage} method={method} history={tracking.history} />
        </div>

        {/* The next move, in the order it happens. */}
        <div className="flex flex-wrap items-center justify-end gap-2 border-t border-rule px-5 py-4">
          {completed ? (
            <Button variant="primary" onClick={() => setPassOpen(true)}>
              <Icon name="idCard" className="h-4 w-4" />
              {t('Print boarding pass')}
            </Button>
          ) : !method ? (
            <p className="mr-auto text-sm text-warn">{t('Waiting for the customer to choose pickup or delivery — or add it for them below.')}</p>
          ) : (
            <>
              <p className="mr-auto text-xs text-ink3">{t('Each change is shown to the customer and notified to them.')}</p>
              {stage !== 'preparing' && (
                <Button disabled={changeStage.isPending} onClick={() => move('preparing')}>
                  {t('Back to preparing')}
                </Button>
              )}
              {stage !== 'dispatched' && (
                <Button disabled={changeStage.isPending} onClick={() => move('dispatched')}>
                  <Icon name={method === 'pickup' ? 'warehouse' : 'truck'} className="h-4 w-4" />
                  {t(method === 'pickup' ? 'Mark ready for pickup' : 'Mark out for delivery')}
                </Button>
              )}
              <Button variant="primary" onClick={() => setCompleting(true)}>
                <Icon name="check" className="h-4 w-4" />
                {t(method === 'pickup' ? 'Complete — picked up' : 'Complete — delivered')}
              </Button>
            </>
          )}
        </div>
        {changeStage.error ? (
          <div className="px-5 pb-4">
            <ErrorBanner error={changeStage.error} />
          </div>
        ) : null}
      </section>

      <div className="grid gap-5 lg:grid-cols-2">
        {/* Receiving method. */}
        <section className="rounded-2xl border border-rule bg-panel p-5 shadow-card">
          <div className="mb-3 flex items-center justify-between gap-2">
            <h3 className="flex items-center gap-2 text-[15px] font-semibold text-ink">
              <Icon name={method === 'pickup' ? 'warehouse' : 'truck'} className="h-4 w-4 text-brand" />
              {t('Receiving method')}
            </h3>
            {!completed && (
              <Button size="sm" variant={method ? 'secondary' : 'primary'} onClick={() => setEditingMethod(true)}>
                {method ? (
                  <>
                    <Icon name="lock" className="h-3.5 w-3.5" />
                    {t('Edit')}
                  </>
                ) : (
                  t('Add method')
                )}
              </Button>
            )}
          </div>
          {method ? (
            <dl className="grid gap-3 text-sm">
              <Detail label={t('Method')} value={t(methodLabel(method))} />
              {method === 'pickup' ? (
                <Detail
                  label={t('Pickup warehouse')}
                  value={[tracking.pickupLocationName, tracking.pickupLocationAddress].filter(Boolean).join(' — ')}
                />
              ) : (
                <>
                  <Detail label={t('Delivery address')} value={tracking.deliveryAddress} />
                  <Detail label={t('Contact number')} value={tracking.deliveryContact} mono />
                </>
              )}
              <Detail
                label={t('Set')}
                value={`${when(tracking.receiveMethodSetAt)} · ${tracking.receiveMethodSetByName ?? t('by the customer')}`}
              />
            </dl>
          ) : (
            <p className="rounded-xl bg-warnsoft p-3 text-sm text-warn">{t('Not chosen yet.')}</p>
          )}
          {completed && (
            <p className="mt-3 rounded-xl bg-oksoft p-3 text-sm text-ok">
              {t('Handed over from {{store}} on {{when}} by {{who}}.', {
                store: tracking.handoverLocationName ?? '—',
                when: when(tracking.completedAt),
                who: tracking.completedByName ?? '—',
              })}
            </p>
          )}
        </section>

        {/* History. */}
        <section className="rounded-2xl border border-rule bg-panel p-5 shadow-card">
          <h3 className="mb-3 text-[15px] font-semibold text-ink">{t('History')}</h3>
          <ol className="relative ml-2 border-l-2 border-rule pl-5">
            {[...(tracking.history ?? [])].reverse().map((step, index) => (
              <li key={index} className="relative pb-4 last:pb-0">
                <span
                  className={
                    'absolute top-1 -left-[27px] h-3.5 w-3.5 rounded-full ring-4 ring-panel ' +
                    (index === 0 ? 'bg-brand' : 'bg-rulestrong')
                  }
                />
                <p className="text-sm font-semibold text-ink">{t(stageTitle(step.stage, method))}</p>
                {step.note && <p className="text-sm text-ink2">{step.note}</p>}
                <p className="text-xs text-ink3">
                  {when(step.at)} · {step.actorName ?? t('Customer')}
                </p>
              </li>
            ))}
          </ol>
        </section>
      </div>

      {/* Contents. */}
      <section className="rounded-2xl border border-rule bg-panel p-5 shadow-card">
        <h3 className="mb-3 text-[15px] font-semibold text-ink">
          {t('Pack contents · {{count}} items', { count: (row.packItems ?? []).length })}
        </h3>
        <ul className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
          {(row.packItems ?? []).map((item) => (
            <li key={item.itemId} className="flex items-center gap-3 rounded-xl border border-rule p-2.5">
              {item.imageId ? (
                <img src={itemImageUrl(item.imageId)} alt="" className="h-12 w-12 flex-none rounded-lg object-cover" />
              ) : (
                <span className="flex h-12 w-12 flex-none items-center justify-center rounded-lg bg-panel2 text-ink3">
                  <Icon name="package" className="h-5 w-5" />
                </span>
              )}
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-semibold text-ink">{item.name}</p>
                <p className="font-mono text-[11px] text-ink3">{item.sku}</p>
              </div>
              <span className="nums rounded-full bg-brandsoft px-2 py-0.5 text-xs font-bold text-brand">×{item.quantity}</span>
            </li>
          ))}
        </ul>
      </section>

      {editingMethod && <MethodModal row={row} onClose={() => setEditingMethod(false)} />}
      {completing && (
        <CompleteModal
          row={row}
          onClose={() => setCompleting(false)}
          onCompleted={() => {
            setCompleting(false);
            setPassOpen(true);
          }}
        />
      )}
      {passOpen && completed && <BoardingPassModal pass={passFromAdmin(row)} onClose={() => setPassOpen(false)} />}
    </div>
  );
}

function Detail({ label, value, mono }: { label: string; value?: string | null; mono?: boolean }) {
  return (
    <div>
      <dt className="text-[11px] tracking-wide text-ink3 uppercase">{label}</dt>
      <dd className={`text-ink ${mono ? 'font-mono' : ''}`}>{value || '—'}</dd>
    </div>
  );
}

/**
 * Adding a receiving method, or changing one. Changing overrides what the customer chose, so it
 * asks for the administrator's own password — the server refuses it without.
 */
function MethodModal({ row, onClose }: { row: RewardEntitlement; onClose: () => void }) {
  const { t } = useTranslation();
  const tracking = row.tracking!;
  const changing = Boolean(tracking.receiveMethod);
  const stores = useLocations();
  const save = useSetReceiveMethod();

  const [method, setMethod] = useState<'pickup' | 'delivery'>(
    (tracking.receiveMethod as 'pickup' | 'delivery' | undefined) ?? 'pickup',
  );
  const [pickupLocationId, setPickupLocationId] = useState(tracking.pickupLocationId ?? '');
  const [address, setAddress] = useState(tracking.deliveryAddress ?? '');
  const [contact, setContact] = useState(tracking.deliveryContact ?? row.distributorMobile ?? '');
  const [password, setPassword] = useState('');

  const activeStores = (stores.data ?? []).filter((store) => store.active);
  const ready =
    (method === 'pickup' ? Boolean(pickupLocationId) : address.trim() && contact.trim().length >= 7) &&
    (!changing || password.length > 0);

  return (
    <Modal title={changing ? t('Change receiving method') : t('Add receiving method')} onClose={onClose}>
      <form
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault();
          if (!row.id) return;
          const body: ReceiveMethodBody =
            method === 'pickup'
              ? { method, pickupLocationId }
              : { method, deliveryAddress: address.trim(), deliveryContact: contact.trim() };
          save.mutate({ id: row.id, ...body, password: changing ? password : undefined }, { onSuccess: onClose });
        }}
      >
        <div className="grid grid-cols-2 gap-2">
          {(['pickup', 'delivery'] as const).map((option) => (
            <button
              key={option}
              type="button"
              aria-pressed={method === option}
              onClick={() => setMethod(option)}
              className={
                'flex items-center gap-2 rounded-xl border-2 p-3 text-sm font-semibold transition-colors ' +
                (method === option ? 'border-brand bg-brandsoft text-brand' : 'border-rule text-ink2 hover:border-brand/40')
              }
            >
              <Icon name={option === 'pickup' ? 'warehouse' : 'truck'} className="h-4 w-4" />
              {t(methodLabel(option))}
            </button>
          ))}
        </div>

        {method === 'pickup' ? (
          <Field label={t('Pickup warehouse')}>
            <Select required value={pickupLocationId} onChange={(event) => setPickupLocationId(event.target.value)}>
              <option value="">{t('Choose a warehouse…')}</option>
              {activeStores.map((store) => (
                <option key={store.id} value={store.id ?? ''}>
                  {store.name}
                  {store.address ? ` — ${store.address}` : ''}
                </option>
              ))}
            </Select>
          </Field>
        ) : (
          <>
            <Field label={t('Delivery address')}>
              <textarea
                rows={3}
                maxLength={500}
                required
                value={address}
                onChange={(event) => setAddress(event.target.value)}
                className="w-full rounded-lg border border-rulestrong bg-panel px-3 py-2 text-sm text-ink shadow-xs outline-none focus:border-brand focus:ring-2 focus:ring-brand/20"
              />
            </Field>
            <Field label={t('Contact number')}>
              <Input type="tel" required maxLength={20} value={contact} onChange={(event) => setContact(event.target.value)} />
            </Field>
          </>
        )}

        {changing && (
          <div className="rounded-xl border border-warn/30 bg-warnsoft p-4">
            <p className="mb-2 flex items-center gap-2 text-sm font-semibold text-warn">
              <Icon name="lock" className="h-4 w-4" />
              {t('This overrides the method already set. Enter your password to confirm.')}
            </p>
            <PasswordInput
              autoComplete="current-password"
              aria-label={t('Your password')}
              placeholder={t('Your password')}
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
          </div>
        )}

        <ErrorBanner error={save.error} />

        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            {t('Cancel')}
          </Button>
          <Button type="submit" variant="primary" disabled={!ready || save.isPending}>
            {save.isPending ? t('Saving…') : changing ? t('Confirm change') : t('Save')}
          </Button>
        </div>
      </form>
    </Modal>
  );
}

/** The last stage: which warehouse it left from. Final, and it closes the customer's account. */
function CompleteModal({
  row,
  onClose,
  onCompleted,
}: {
  row: RewardEntitlement;
  onClose: () => void;
  onCompleted: () => void;
}) {
  const { t } = useTranslation();
  const tracking = row.tracking!;
  const stores = useLocations();
  const complete = useCompleteReward();
  const activeStores = (stores.data ?? []).filter((store) => store.active);
  // For a pickup, the warehouse they chose; otherwise the store it was issued from.
  const [locationId, setLocationId] = useState(tracking.pickupLocationId ?? row.issuedFromLocationId ?? '');
  const [note, setNote] = useState('');
  const pickup = tracking.receiveMethod === 'pickup';

  return (
    <Modal title={pickup ? t('Complete — picked up') : t('Complete — delivered')} onClose={onClose}>
      <form
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault();
          if (!row.id || !locationId) return;
          complete.mutate({ id: row.id, locationId, note: note.trim() || undefined }, { onSuccess: onCompleted });
        }}
      >
        <p className="text-sm text-ink2">
          {t('{{pack}} for {{who}} ({{id}}), tracking {{number}}.', {
            pack: row.itemSetName ?? '',
            who: row.distributorName ?? '',
            id: row.businessId ?? '',
            number: tracking.trackingNumber ?? '',
          })}
        </p>
        <Field label={t('Handed over from warehouse')} hint={t('Printed on the boarding pass')}>
          <Select required value={locationId} onChange={(event) => setLocationId(event.target.value)}>
            <option value="">{t('Choose a warehouse…')}</option>
            {activeStores.map((store) => (
              <option key={store.id} value={store.id ?? ''}>
                {store.name}
              </option>
            ))}
          </Select>
        </Field>
        <Field label={t('Note')} hint={t('Optional — who signed for it, or anything worth recording')}>
          <Input maxLength={500} value={note} onChange={(event) => setNote(event.target.value)} />
        </Field>
        <p className="flex items-start gap-2 rounded-xl bg-warnsoft p-3 text-sm text-warn">
          <Icon name="alert" className="mt-0.5 h-4 w-4 flex-none" />
          {t('This is the final stage. It cannot be undone, and it closes the customer’s business account. The boarding pass opens next, ready to print.')}
        </p>
        <ErrorBanner error={complete.error} />
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            {t('Cancel')}
          </Button>
          <Button type="submit" variant="primary" disabled={!locationId || complete.isPending}>
            {complete.isPending ? t('Completing…') : t('Complete and print pass')}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
