import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  portalPictureUrl,
  useChooseReceiveMethod,
  usePickupPoints,
  type PortalView,
  type RewardSnapshot,
} from '../api/portal';
import type { RewardPackLine } from '../api/types';
import { BoardingPassModal, PackStepper, type BoardingPassData } from '../components/PackTracking';
import { Icon } from '../components/icons';
import { Button, ErrorBanner, Field, Input, Modal } from '../components/ui';
import { methodLabel, stageDescription, stageTitle } from '../lib/packTracking';
import { CopyButton, GlassCard, SectionTitle } from './portalUi';

const when = (iso?: string | null) =>
  iso ? new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' }) : '—';

/** A boarding pass from the customer's own view of their pack. */
export function passFromPortal(me: PortalView, reward: RewardSnapshot): BoardingPassData {
  const tracking = reward.tracking!;
  return {
    trackingNumber: tracking.trackingNumber ?? '',
    packCode: reward.itemSetCode,
    packName: reward.itemSetName,
    items: reward.items ?? [],
    customerName: me.fullName,
    businessId: me.businessId,
    customerMobile: me.mobile,
    customerEmail: me.email,
    method: tracking.receiveMethod,
    pickupLocationName: tracking.pickupLocationName,
    deliveryAddress: tracking.deliveryAddress,
    deliveryContact: tracking.deliveryContact,
    warehouseName: tracking.handoverLocationName,
    warehouseAddress: tracking.handoverLocationAddress,
    issuedAt: reward.issuedAt,
    completedAt: tracking.completedAt,
    completedByName: tracking.completedByName,
  };
}

/**
 * The item pack on the dashboard, once every level is complete.
 *
 * Large on purpose: at this point it is the only thing the customer is waiting for. It shows what
 * the pack is — picture, contents — and, once the office has issued it, where it is: the tracking
 * number, the four stages, and the one thing the customer has to do, which is say how they will
 * receive it.
 */
export function PackJourneyCard({ me }: { me: PortalView }) {
  const { t } = useTranslation();
  const reward = me.reward;
  const [passOpen, setPassOpen] = useState(false);

  if (!reward) return null;
  const tracking = reward.tracking;
  const items = reward.items ?? [];

  return (
    <section className="relative mt-5 overflow-hidden rounded-3xl bg-linear-to-br from-[#fff3d4] via-[#ffe6b0] to-[#ffd98a] shadow-[0_20px_44px_-20px_rgba(224,154,26,0.8)]">
      <div aria-hidden className="pointer-events-none absolute -top-16 -right-10 h-56 w-56 rounded-full bg-white/35" />
      <div aria-hidden className="pointer-events-none absolute -bottom-24 left-1/4 h-64 w-64 rounded-full bg-[#ffc85a]/30 blur-2xl" />

      <div className="relative grid gap-6 p-6 sm:p-8 lg:grid-cols-[300px_minmax(0,1fr)]">
        {/* The pack itself. */}
        <div className="self-start overflow-hidden rounded-2xl bg-white/70 ring-1 ring-white shadow-sm">
          {reward.itemSetImageId ? (
            <img src={portalPictureUrl(reward.itemSetImageId)} alt="" className="h-52 w-full object-cover lg:h-64" />
          ) : (
            <div className="flex h-52 items-center justify-center text-[#e0891a] lg:h-64">
              <Icon name="gift" className="h-16 w-16" />
            </div>
          )}
        </div>

        <div className="min-w-0">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div className="min-w-0">
              <p className="inline-flex items-center gap-1.5 rounded-full bg-white/70 px-3 py-1 text-[11px] font-bold tracking-[0.14em] text-[#b86e0c] uppercase">
                <Icon name="sparkles" className="h-3.5 w-3.5" />
                {tracking ? t('Your item pack is on its way') : t('You have earned your item pack')}
              </p>
              <h2 className="mt-2 text-2xl font-extrabold tracking-tight text-ink sm:text-3xl">
                {reward.itemSetName}
                <span className="ml-2 align-middle font-mono text-xs font-semibold text-ink3">{reward.itemSetCode}</span>
              </h2>
              {reward.itemSetDescription && (
                <p className="mt-1 max-w-2xl text-sm text-ink2">{reward.itemSetDescription}</p>
              )}
            </div>
            {tracking?.trackingNumber && (
              <div className="rounded-2xl bg-white/85 px-4 py-2.5 shadow-sm ring-1 ring-white">
                <p className="text-[10px] font-bold tracking-[0.16em] text-ink3 uppercase">{t('Tracking number')}</p>
                <div className="flex items-center gap-2">
                  <p className="font-mono text-lg font-bold tracking-wider text-ink">{tracking.trackingNumber}</p>
                  <CopyButton value={tracking.trackingNumber} label={t('Copy')} />
                </div>
              </div>
            )}
          </div>

          {tracking ? (
            <>
              <div className="mt-6 rounded-2xl bg-white/75 p-4 ring-1 ring-white sm:p-5">
                <PackStepper stage={tracking.stage} method={tracking.receiveMethod} history={tracking.history} />
                <p className="mt-4 rounded-xl bg-[#fff8e8] px-4 py-3 text-center text-sm font-medium text-ink2">
                  <span className="font-bold text-ink">{t(stageTitle(tracking.stage, tracking.receiveMethod))}: </span>
                  {t(stageDescription(tracking.stage, tracking.receiveMethod))}
                </p>
              </div>
              {tracking.receiveMethod ? (
                <ReceivingDetails reward={reward} />
              ) : (
                <ChooseReceiveMethod mobile={me.mobile} />
              )}
              {tracking.stage === 'completed' && (
                <div className="mt-4 flex justify-end">
                  <Button variant="primary" onClick={() => setPassOpen(true)}>
                    <Icon name="idCard" className="h-4 w-4" />
                    {t('View boarding pass')}
                  </Button>
                </div>
              )}
            </>
          ) : (
            <p className="mt-5 rounded-2xl bg-white/70 px-4 py-3 text-sm text-ink2">
              <span className="font-bold text-ink">{t('Every level is complete.')} </span>
              {t('The office will issue your pack shortly. Once they do, a tracking number appears here and you choose how to receive it.')}
            </p>
          )}
        </div>
      </div>

      {items.length > 0 && (
        <div className="relative border-t border-white/60 bg-white/40 px-6 py-5 sm:px-8">
          <p className="mb-3 text-xs font-bold tracking-[0.14em] text-ink2 uppercase">
            {t("What's inside · {{count}} items", { count: items.length })}
          </p>
          <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {items.map((item) => (
              <PackLineCard key={item.itemId} item={item} />
            ))}
          </ul>
        </div>
      )}

      {passOpen && tracking && (
        <BoardingPassModal pass={passFromPortal(me, reward)} onClose={() => setPassOpen(false)} />
      )}
    </section>
  );
}

function PackLineCard({ item }: { item: RewardPackLine }) {
  return (
    <li className="flex items-center gap-3 rounded-2xl bg-white/85 p-2.5 ring-1 ring-white">
      {item.imageId ? (
        <img src={portalPictureUrl(item.imageId)} alt="" className="h-14 w-14 flex-none rounded-xl object-cover" />
      ) : (
        <span className="flex h-14 w-14 flex-none items-center justify-center rounded-xl bg-panel2 text-ink3">
          <Icon name="package" className="h-6 w-6" />
        </span>
      )}
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-semibold text-ink">{item.name}</p>
        <p className="font-mono text-[11px] text-ink3">{item.sku}</p>
      </div>
      <span className="nums flex-none rounded-full bg-[#fff1d6] px-2.5 py-0.5 text-xs font-bold text-[#b86e0c]">
        ×{item.quantity}
      </span>
    </li>
  );
}

/** What was chosen, shown locked: from here only the office can change it. */
function ReceivingDetails({ reward }: { reward: RewardSnapshot }) {
  const { t } = useTranslation();
  const tracking = reward.tracking!;
  const pickup = tracking.receiveMethod === 'pickup';
  return (
    <div className="mt-4 flex flex-col gap-3 rounded-2xl bg-white/75 p-4 ring-1 ring-white sm:flex-row sm:items-center">
      <span className="flex h-11 w-11 flex-none items-center justify-center rounded-xl bg-linear-to-br from-[#f6c453] to-[#e0891a] text-white">
        <Icon name={pickup ? 'warehouse' : 'truck'} className="h-5 w-5" />
      </span>
      <div className="min-w-0 flex-1">
        <p className="text-sm font-bold text-ink">{t(methodLabel(tracking.receiveMethod))}</p>
        <p className="text-sm text-ink2">
          {pickup
            ? [tracking.pickupLocationName, tracking.pickupLocationAddress].filter(Boolean).join(' — ')
            : `${tracking.deliveryAddress ?? ''} · ${tracking.deliveryContact ?? ''}`}
        </p>
      </div>
      <span className="inline-flex items-center gap-1.5 self-start rounded-full bg-panel2 px-3 py-1 text-xs font-semibold text-ink3 sm:self-center">
        <Icon name="lock" className="h-3.5 w-3.5" />
        {t('Locked — contact the office to change')}
      </span>
    </div>
  );
}

/**
 * The customer's one decision. Confirmed before it is sent, because it cannot be taken back from
 * this side.
 */
function ChooseReceiveMethod({ mobile }: { mobile?: string | null }) {
  const { t } = useTranslation();
  const [method, setMethod] = useState<'pickup' | 'delivery' | null>(null);
  const [pickupLocationId, setPickupLocationId] = useState('');
  const [address, setAddress] = useState('');
  const [contact, setContact] = useState(mobile ?? '');
  const [confirming, setConfirming] = useState(false);
  const points = usePickupPoints(method === 'pickup');
  const choose = useChooseReceiveMethod();

  const chosenPoint = (points.data ?? []).find((point) => point.id === pickupLocationId);
  const ready =
    method === 'pickup'
      ? Boolean(pickupLocationId)
      : method === 'delivery'
        ? address.trim().length > 0 && contact.trim().length >= 7
        : false;

  const submit = () =>
    choose.mutate(
      method === 'pickup'
        ? { method: 'pickup', pickupLocationId }
        : { method: 'delivery', deliveryAddress: address.trim(), deliveryContact: contact.trim() },
      { onSuccess: () => setConfirming(false) },
    );

  return (
    <div className="mt-4 rounded-2xl bg-white p-5 shadow-sm ring-2 ring-[#f6c453]">
      <p className="text-base font-bold text-ink">{t('How would you like to receive your pack?')}</p>
      <p className="mt-0.5 text-sm text-ink3">{t('Choose carefully — once you submit, only the office can change it.')}</p>

      <div className="mt-4 grid gap-3 sm:grid-cols-2">
        <MethodOption
          active={method === 'pickup'}
          icon="warehouse"
          title={t('Pick up')}
          body={t('Collect it yourself from one of our warehouses.')}
          onClick={() => setMethod('pickup')}
        />
        <MethodOption
          active={method === 'delivery'}
          icon="truck"
          title={t('Delivery')}
          body={t('We bring it to your address.')}
          onClick={() => setMethod('delivery')}
        />
      </div>

      {method === 'pickup' && (
        <div className="mt-4">
          <p className="mb-2 text-sm font-semibold text-ink">{t('Choose a warehouse')}</p>
          {points.isLoading ? (
            <p className="text-sm text-ink3">{t('Loading warehouses…')}</p>
          ) : (
            <div className="grid gap-2 sm:grid-cols-2">
              {(points.data ?? []).map((point) => (
                <button
                  key={point.id}
                  type="button"
                  onClick={() => setPickupLocationId(point.id ?? '')}
                  className={
                    'rounded-xl border p-3 text-left transition-all ' +
                    (pickupLocationId === point.id
                      ? 'border-brand bg-brandsoft ring-2 ring-brand/25'
                      : 'border-rule bg-white hover:border-brand/40')
                  }
                >
                  <p className="text-sm font-semibold text-ink">{point.name}</p>
                  <p className="text-xs text-ink3">{point.address || t('Address on request')}</p>
                </button>
              ))}
            </div>
          )}
          <ErrorBanner error={points.error} />
        </div>
      )}

      {method === 'delivery' && (
        <div className="mt-4 grid gap-3 sm:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
          <Field label={t('Delivery address')}>
            <textarea
              rows={3}
              maxLength={500}
              value={address}
              onChange={(event) => setAddress(event.target.value)}
              placeholder={t('House number, street, city')}
              className="w-full rounded-lg border border-rulestrong bg-panel px-3 py-2 text-sm text-ink shadow-xs outline-none focus:border-brand focus:ring-2 focus:ring-brand/20"
            />
          </Field>
          <Field label={t('Contact number')} hint={t('The driver calls this number')}>
            <Input type="tel" maxLength={20} value={contact} onChange={(event) => setContact(event.target.value)} />
          </Field>
        </div>
      )}

      {method && (
        <div className="mt-4 flex justify-end">
          <Button variant="primary" disabled={!ready} onClick={() => setConfirming(true)}>
            {t('Submit')}
            <Icon name="arrowRight" className="h-4 w-4" />
          </Button>
        </div>
      )}

      {confirming && method && (
        <Modal title={t('Confirm how you receive your pack')} onClose={() => setConfirming(false)}>
          <div className="flex flex-col gap-4">
            <div className="rounded-xl bg-panel2 p-4 text-sm">
              <p className="font-semibold text-ink">{t(methodLabel(method))}</p>
              <p className="text-ink2">
                {method === 'pickup'
                  ? [chosenPoint?.name, chosenPoint?.address].filter(Boolean).join(' — ')
                  : `${address.trim()} · ${contact.trim()}`}
              </p>
            </div>
            <p className="flex items-start gap-2 rounded-xl bg-warnsoft p-3 text-sm text-warn">
              <Icon name="lock" className="mt-0.5 h-4 w-4 flex-none" />
              {t('After you submit you cannot change this yourself. If something changes later, contact the office.')}
            </p>
            <ErrorBanner error={choose.error} />
            <div className="flex justify-end gap-2">
              <Button variant="ghost" onClick={() => setConfirming(false)}>
                {t('Go back')}
              </Button>
              <Button variant="primary" disabled={choose.isPending} onClick={submit}>
                {choose.isPending ? t('Submitting…') : t('Confirm and submit')}
              </Button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  );
}

function MethodOption({
  active,
  icon,
  title,
  body,
  onClick,
}: {
  active: boolean;
  icon: 'warehouse' | 'truck';
  title: string;
  body: string;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={
        'flex items-center gap-3 rounded-2xl border-2 p-4 text-left transition-all ' +
        (active
          ? 'border-[#e0891a] bg-[#fff8e8] shadow-sm'
          : 'border-rule bg-white hover:-translate-y-0.5 hover:border-[#f6c453]')
      }
    >
      <span
        className={
          'flex h-11 w-11 flex-none items-center justify-center rounded-xl ' +
          (active ? 'bg-linear-to-br from-[#f6c453] to-[#e0891a] text-white' : 'bg-panel2 text-ink2')
        }
      >
        <Icon name={icon} className="h-5 w-5" />
      </span>
      <span>
        <span className="block text-sm font-bold text-ink">{title}</span>
        <span className="block text-xs text-ink3">{body}</span>
      </span>
    </button>
  );
}

// ================================================================== completed account

/**
 * The dashboard once the pack has been handed over: the account is closed, and this is its record.
 *
 * Three things: a clear "you finished", the previous business account (marked closed), and its
 * history. Plus the way forward — registering again for another pack. That button is the UI only
 * for now; the flow behind it comes later.
 */
export function CompletedAccount({ me }: { me: PortalView }) {
  const { t } = useTranslation();
  const [passOpen, setPassOpen] = useState(false);
  const [registerOpen, setRegisterOpen] = useState(false);
  const reward = me.reward;
  const tracking = reward?.tracking;
  const firstName = (me.fullName ?? '').split(' ')[0];
  const children = me.children ?? [];

  const history = buildHistory(me);

  return (
    <>
      <section className="relative overflow-hidden rounded-3xl bg-linear-to-br from-[#0b7a6e] via-[#0f8d80] to-[#3f5bd8] p-7 text-white shadow-[0_20px_40px_-18px_rgba(11,122,110,0.65)] sm:p-9">
        <div aria-hidden className="pointer-events-none absolute -top-20 -right-16 h-64 w-64 rounded-full bg-white/10" />
        <div aria-hidden className="pointer-events-none absolute -bottom-28 left-1/3 h-72 w-72 rounded-full bg-[#7ee0d3]/15 blur-2xl" />
        <div className="relative flex flex-col gap-6 lg:flex-row lg:items-center lg:justify-between">
          <div className="min-w-0">
            <p className="inline-flex items-center gap-1.5 rounded-full bg-white/15 px-3 py-1 text-xs font-semibold ring-1 ring-white/20">
              <Icon name="trophy" className="h-3.5 w-3.5" />
              {t('Journey complete')}
            </p>
            <h1 className="mt-4 text-3xl leading-tight font-extrabold tracking-tight sm:text-[34px]">
              {t('Congratulations, {{name}}!', { name: firstName || me.fullName || '' })}
            </h1>
            <p className="mt-2 max-w-xl text-[15px] text-white/85">
              {t('Your item pack {{pack}} has been {{how}}. This business account has done everything it set out to do, so it is now closed — its full history is below.', {
                pack: reward?.itemSetName ?? '',
                how: tracking?.receiveMethod === 'pickup' ? t('picked up') : t('delivered'),
              })}
            </p>
          </div>
          <div className="flex flex-none flex-col gap-2.5 sm:flex-row lg:flex-col">
            <button
              type="button"
              onClick={() => setRegisterOpen(true)}
              className="inline-flex h-12 items-center justify-center gap-2 rounded-full bg-white px-6 text-sm font-bold text-brand shadow-sm transition-transform hover:-translate-y-0.5"
            >
              <Icon name="userPlus" className="h-4 w-4" />
              {t('Register again for another pack')}
            </button>
            {tracking && (
              <button
                type="button"
                onClick={() => setPassOpen(true)}
                className="inline-flex h-12 items-center justify-center gap-2 rounded-full bg-white/15 px-6 text-sm font-semibold ring-1 ring-white/25 transition-colors hover:bg-white/25"
              >
                <Icon name="idCard" className="h-4 w-4" />
                {t('View boarding pass')}
              </button>
            )}
          </div>
        </div>
      </section>

      <div className="mt-5 grid gap-5 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.4fr)]">
        {/* The previous business account, as a card that has been closed. */}
        <div className="flex flex-col gap-5">
          <section className="relative flex min-h-[230px] flex-col justify-between overflow-hidden rounded-3xl bg-linear-to-br from-[#4b5563] via-[#374151] to-[#1f2937] p-6 text-white shadow-[0_20px_40px_-18px_rgba(15,27,45,0.6)]">
            <span className="absolute top-6 right-[-40px] rotate-35 bg-[#ef4444]/90 px-12 py-1 text-[11px] font-extrabold tracking-[0.2em] uppercase shadow">
              {t('Closed')}
            </span>
            <div>
              <p className="text-[11px] font-bold tracking-[0.16em] text-white/60 uppercase">{t('Previous business account')}</p>
              <p className="mt-0.5 text-sm font-semibold">MLM Sittu</p>
            </div>
            <div>
              <p className="text-[11px] font-bold tracking-[0.16em] text-white/60 uppercase">{t('Business ID')}</p>
              <p className="font-mono text-4xl font-bold tracking-[0.12em] text-white/90">{me.businessId || '—'}</p>
            </div>
            <div className="flex items-end justify-between gap-3">
              <div className="min-w-0">
                <p className="text-[10px] font-bold tracking-[0.16em] text-white/60 uppercase">{t('Name')}</p>
                <p className="truncate text-sm font-semibold">{me.fullName}</p>
              </div>
              <div className="flex gap-5 text-right">
                <div>
                  <p className="text-[10px] font-bold tracking-[0.16em] text-white/60 uppercase">{t('Since')}</p>
                  <p className="text-sm font-semibold">{dateOnly(me.approvedAt)}</p>
                </div>
                <div>
                  <p className="text-[10px] font-bold tracking-[0.16em] text-white/60 uppercase">{t('Completed')}</p>
                  <p className="text-sm font-semibold">{dateOnly(tracking?.completedAt)}</p>
                </div>
              </div>
            </div>
          </section>

          <div className="grid grid-cols-2 gap-4">
            <SummaryTile icon="trophy" label={t('Levels completed')} value={`${me.stages?.stagesCompleted ?? 0}/${me.stages?.totalStages ?? 0}`} />
            <SummaryTile icon="users" label={t('Direct referrals')} value={String(children.length)} />
            <SummaryTile icon="gift" label={t('Pack received')} value={reward?.itemSetName ?? '—'} small />
            <SummaryTile icon="package" label={t('Tracking number')} value={tracking?.trackingNumber ?? '—'} small mono />
          </div>
        </div>

        <GlassCard>
          <SectionTitle eyebrow={t('History')} title={t('Your journey with this account')} />
          <ol className="relative ml-2 border-l-2 border-rule pl-6">
            {history.map((entry, index) => (
              <li key={index} className="relative pb-5 last:pb-0">
                <span
                  className={
                    'absolute top-0.5 -left-[33px] flex h-6 w-6 items-center justify-center rounded-full ring-4 ring-white ' +
                    (index === history.length - 1 ? 'bg-linear-to-br from-brandbright to-brand text-white' : 'bg-brandsoft text-brand')
                  }
                >
                  <Icon name={entry.icon} className="h-3.5 w-3.5" />
                </span>
                <p className="text-sm font-semibold text-ink">{t(entry.title)}</p>
                {entry.body && <p className="text-sm text-ink2">{entry.body}</p>}
                <p className="text-xs text-ink3">{when(entry.at)}</p>
              </li>
            ))}
          </ol>
        </GlassCard>
      </div>

      {passOpen && reward && tracking && (
        <BoardingPassModal pass={passFromPortal(me, reward)} onClose={() => setPassOpen(false)} />
      )}
      {registerOpen && (
        <Modal title={t('Register again')} onClose={() => setRegisterOpen(false)}>
          <div className="flex flex-col items-center gap-3 text-center">
            <span className="flex h-14 w-14 items-center justify-center rounded-2xl bg-brandsoft text-brand">
              <Icon name="sparkles" className="h-7 w-7" />
            </span>
            <p className="text-base font-bold text-ink">{t('Start a new business registration')}</p>
            <p className="text-sm text-ink2">
              {t('Registering again for another item pack is coming soon. You will choose a new pack and start a fresh journey — this account’s history stays here.')}
            </p>
            <Button variant="primary" onClick={() => setRegisterOpen(false)}>
              {t('OK')}
            </Button>
          </div>
        </Modal>
      )}
    </>
  );
}

const dateOnly = (iso?: string | null) =>
  iso ? new Date(iso).toLocaleDateString(undefined, { dateStyle: 'medium' }) : '—';

type HistoryEntry = {
  title: string;
  body?: string;
  at?: string | null;
  icon: 'user' | 'check' | 'trophy' | 'gift' | 'truck' | 'warehouse' | 'package';
};

/** Everything that happened to this account, oldest first, from what the portal already knows. */
function buildHistory(me: PortalView): HistoryEntry[] {
  const reward = me.reward;
  const tracking = reward?.tracking;
  const entries: HistoryEntry[] = [
    { title: 'Account created', at: me.joinedAt, icon: 'user' },
    { title: 'Business registration submitted', at: me.registration?.submittedAt, icon: 'package' },
    {
      title: 'Business registration approved',
      body: me.businessId ? `Business ID ${me.businessId}` : undefined,
      at: me.approvedAt,
      icon: 'check',
    },
    { title: 'Every level completed', at: me.stages?.allStagesCompletedAt ?? reward?.becameEligibleAt, icon: 'trophy' },
    { title: 'Item pack issued', body: reward?.itemSetName ?? undefined, at: reward?.issuedAt, icon: 'gift' },
  ];
  // The pack's own journey after issue, skipping the first step (it is the issue itself).
  for (const step of (tracking?.history ?? []).slice(1)) {
    entries.push({
      title: stageTitle(step.stage, tracking?.receiveMethod),
      body: step.note ?? undefined,
      at: step.at,
      icon: step.stage === 'completed' ? 'check' : tracking?.receiveMethod === 'pickup' ? 'warehouse' : 'truck',
    });
  }
  return entries.filter((entry) => entry.at);
}

function SummaryTile({
  icon,
  label,
  value,
  small,
  mono,
}: {
  icon: 'trophy' | 'users' | 'gift' | 'package';
  label: string;
  value: string;
  small?: boolean;
  mono?: boolean;
}) {
  return (
    <div className="flex items-center gap-3 rounded-2xl bg-white/85 p-4 ring-1 ring-white shadow-[0_10px_30px_-14px_rgba(16,24,40,0.2)]">
      <span className="flex h-10 w-10 flex-none items-center justify-center rounded-xl bg-brandsoft text-brand">
        <Icon name={icon} className="h-5 w-5" />
      </span>
      <div className="min-w-0">
        <p className="text-xs text-ink3">{label}</p>
        <p className={`truncate font-extrabold text-ink ${small ? 'text-sm' : 'text-xl'} ${mono ? 'font-mono' : ''}`}>{value}</p>
      </div>
    </div>
  );
}
