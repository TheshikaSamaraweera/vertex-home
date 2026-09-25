import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { useTranslation } from 'react-i18next';
import QRCode from 'qrcode';
import type { RewardPackLine, RewardTrackingStep } from '../api/types';
import { PACK_STAGES, methodLabel, stageIndex, stageTitle } from '../lib/packTracking';
import { Icon } from './icons';
import { Button } from './ui';

const when = (iso?: string | null) =>
  iso ? new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' }) : '';

/**
 * The four stages of an issued pack, drawn as a journey.
 *
 * Shared by the customer's dashboard and the office's tracking page, so both describe a pack the
 * same way. Each reached stage shows when it was reached.
 */
export function PackStepper({
  stage,
  method,
  history,
  tone = 'light',
}: {
  stage?: string | null;
  method?: string | null;
  history?: RewardTrackingStep[];
  /** `dark` for use on a coloured banner. */
  tone?: 'light' | 'dark';
}) {
  const { t } = useTranslation();
  const current = stageIndex(stage);
  const finished = stage === 'completed';
  // The first time each stage was reached. A method change re-records the current stage, and the
  // stepper should show when it was first reached, not when somebody last edited it.
  const reachedAt = new Map<string, string>();
  for (const step of history ?? []) {
    if (step.stage && step.at && !reachedAt.has(step.stage)) reachedAt.set(step.stage, step.at);
  }
  const dark = tone === 'dark';

  return (
    <ol className="grid grid-cols-4 gap-2">
      {PACK_STAGES.map((key, index) => {
        const done = index < current || finished;
        const active = index === current && !finished;
        return (
          <li key={key} className="relative flex flex-col items-center text-center">
            {index > 0 && (
              <span
                aria-hidden
                className={
                  'absolute top-[18px] right-1/2 h-1 w-full -translate-y-1/2 rounded-full ' +
                  (index <= current || finished
                    ? dark
                      ? 'bg-white'
                      : 'bg-linear-to-r from-brandbright to-brand'
                    : dark
                      ? 'bg-white/25'
                      : 'bg-rule')
                }
              />
            )}
            <span
              className={
                'relative z-10 flex h-9 w-9 items-center justify-center rounded-full text-sm font-bold transition-all ' +
                (done
                  ? dark
                    ? 'bg-white text-brand'
                    : 'bg-linear-to-br from-brandbright to-brand text-white shadow-sm'
                  : active
                    ? dark
                      ? 'bg-white text-[#b86e0c] ring-4 ring-white/35'
                      : 'bg-white text-brand ring-4 ring-brand/25 shadow-sm'
                    : dark
                      ? 'bg-white/20 text-white/80'
                      : 'bg-panel2 text-ink3 ring-1 ring-rule')
              }
            >
              {done ? <Icon name="check" className="h-4 w-4" /> : index + 1}
              {active && (
                <span
                  aria-hidden
                  className={
                    'absolute inset-0 animate-ping rounded-full ' +
                    (dark ? 'bg-white/40' : 'bg-brand/20')
                  }
                />
              )}
            </span>
            <p
              className={
                'mt-2 text-[12px] leading-tight font-semibold sm:text-[13px] ' +
                (dark ? (done || active ? 'text-white' : 'text-white/70') : done || active ? 'text-ink' : 'text-ink3')
              }
            >
              {t(stageTitle(key, method))}
            </p>
            {reachedAt.get(key) && (
              <p className={'mt-0.5 text-[10px] sm:text-[11px] ' + (dark ? 'text-white/75' : 'text-ink3')}>
                {when(reachedAt.get(key))}
              </p>
            )}
          </li>
        );
      })}
    </ol>
  );
}

/** Everything a boarding pass prints. Built from either audience's view of the pack. */
export type BoardingPassData = {
  trackingNumber: string;
  packCode?: string | null;
  packName?: string | null;
  items: RewardPackLine[];
  customerName?: string | null;
  businessId?: string | null;
  customerMobile?: string | null;
  customerEmail?: string | null;
  method?: string | null;
  pickupLocationName?: string | null;
  deliveryAddress?: string | null;
  deliveryContact?: string | null;
  warehouseName?: string | null;
  warehouseAddress?: string | null;
  issuedAt?: string | null;
  completedAt?: string | null;
  completedByName?: string | null;
};

/**
 * The boarding pass: the paper that goes with the pack at hand-over.
 *
 * Shown in an overlay with a Print button. Printing hides everything else on the page — the pass is
 * the only thing on the sheet — and the QR code carries the tracking number so a warehouse can scan
 * it rather than type it.
 */
export function BoardingPassModal({
  pass,
  onClose,
}: {
  pass: BoardingPassData;
  onClose: () => void;
}) {
  const { t } = useTranslation();

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => event.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  return createPortal(
    <div
      className="bp-overlay fixed inset-0 z-[60] flex items-start justify-center overflow-y-auto bg-ink/50 p-4 backdrop-blur-[3px] sm:p-8"
      role="dialog"
      aria-modal="true"
      aria-label={t('Boarding pass')}
      onClick={onClose}
    >
      {/* Only the pass is printed: everything else on the page is hidden for the printer. */}
      <style>{`
        @media print {
          body * { visibility: hidden !important; }
          .bp-sheet, .bp-sheet * { visibility: visible !important; }
          .bp-overlay { position: static !important; background: none !important; padding: 0 !important; backdrop-filter: none !important; }
          .bp-sheet { position: absolute; left: 0; top: 0; width: 100%; box-shadow: none !important; border: 1px solid #999 !important; }
          .bp-noprint { display: none !important; }
          @page { margin: 12mm; }
        }
      `}</style>
      <div className="w-full max-w-3xl" onClick={(event) => event.stopPropagation()}>
        <div className="bp-noprint mb-3 flex items-center justify-end gap-2">
          <Button variant="primary" onClick={() => window.print()}>
            <Icon name="clipboard" className="h-4 w-4" />
            {t('Print boarding pass')}
          </Button>
          <Button onClick={onClose}>{t('Close')}</Button>
        </div>
        <BoardingPass pass={pass} />
      </div>
    </div>,
    document.body,
  );
}

export function BoardingPass({ pass }: { pass: BoardingPassData }) {
  const { t } = useTranslation();
  const [qr, setQr] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    QRCode.toDataURL(pass.trackingNumber, { width: 160, margin: 1, errorCorrectionLevel: 'M' })
      .then((url) => live && setQr(url))
      .catch(() => live && setQr(null));
    return () => {
      live = false;
    };
  }, [pass.trackingNumber]);

  const pickup = pass.method === 'pickup';

  return (
    <article className="bp-sheet overflow-hidden rounded-2xl border border-rule bg-white text-ink shadow-float print:rounded-none">
      {/* Header strip: the brand and the number, like the top of a ticket. */}
      <header className="flex items-center justify-between gap-4 bg-linear-to-r from-[#0b7a6e] to-[#3f5bd8] px-6 py-4 text-white print:bg-none print:text-ink">
        <div>
          <p className="text-[11px] font-bold tracking-[0.18em] uppercase opacity-80">MLM Sittu</p>
          <p className="text-xl font-extrabold tracking-tight">{t('Item pack boarding pass')}</p>
        </div>
        <div className="text-right">
          <p className="text-[10px] font-bold tracking-[0.16em] uppercase opacity-80">{t('Tracking number')}</p>
          <p className="font-mono text-xl font-bold tracking-wider">{pass.trackingNumber}</p>
        </div>
      </header>

      <div className="grid gap-0 sm:grid-cols-[1fr_200px]">
        <div className="flex flex-col gap-5 p-6">
          <div className="grid gap-4 sm:grid-cols-2">
            <PassField label={t('Customer')} value={pass.customerName} strong />
            <PassField label={t('Business ID')} value={pass.businessId} mono strong />
            <PassField label={t('Mobile')} value={pass.customerMobile} />
            <PassField label={t('Email')} value={pass.customerEmail} />
          </div>

          <div className="rounded-xl border border-dashed border-rulestrong p-4">
            <div className="grid gap-4 sm:grid-cols-2">
              <PassField label={t('Item pack')} value={[pass.packName, pass.packCode && `(${pass.packCode})`].filter(Boolean).join(' ')} strong />
              <PassField label={t('Receiving method')} value={t(methodLabel(pass.method))} strong />
              {pickup ? (
                <PassField label={t('Pickup warehouse')} value={pass.pickupLocationName} />
              ) : (
                <>
                  <PassField label={t('Delivery address')} value={pass.deliveryAddress} />
                  <PassField label={t('Delivery contact')} value={pass.deliveryContact} mono />
                </>
              )}
              <PassField
                label={t('Handed over from')}
                value={[pass.warehouseName, pass.warehouseAddress].filter(Boolean).join(' — ')}
                strong
              />
              <PassField label={t('Completed')} value={when(pass.completedAt)} />
              <PassField label={t('Issued')} value={when(pass.issuedAt)} />
              <PassField label={t('Handed over by')} value={pass.completedByName} />
            </div>
          </div>

          <div>
            <p className="mb-2 text-[10px] font-bold tracking-[0.16em] text-ink3 uppercase">
              {t('Contents')}
            </p>
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-rule text-left text-[11px] text-ink3 uppercase">
                  <th className="py-1.5 font-semibold">{t('Item code')}</th>
                  <th className="py-1.5 font-semibold">{t('Item')}</th>
                  <th className="py-1.5 text-right font-semibold">{t('Qty')}</th>
                  <th className="py-1.5 text-right font-semibold">{t('Checked')}</th>
                </tr>
              </thead>
              <tbody>
                {pass.items.map((item) => (
                  <tr key={item.itemId} className="border-b border-rule/70">
                    <td className="py-1.5 font-mono text-xs">{item.sku}</td>
                    <td className="py-1.5">{item.name}</td>
                    <td className="nums py-1.5 text-right font-semibold">{item.quantity}</td>
                    <td className="py-1.5 text-right">
                      <span className="inline-block h-4 w-4 rounded border border-rulestrong align-middle" />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>

        {/* The stub: QR and signatures, torn off in spirit if not in fact. */}
        <aside className="flex flex-col items-center gap-4 border-t border-dashed border-rulestrong bg-panel2 p-6 sm:border-t-0 sm:border-l print:bg-white">
          {qr ? (
            <img src={qr} alt={t('QR code for {{number}}', { number: pass.trackingNumber })} className="h-36 w-36 rounded-lg bg-white p-1" />
          ) : (
            <div className="h-36 w-36 animate-pulse rounded-lg bg-white" />
          )}
          <p className="font-mono text-xs font-semibold">{pass.trackingNumber}</p>
          <div className="mt-auto w-full space-y-6 pt-4">
            <Signature label={t('Customer signature')} />
            <Signature label={t('Officer signature')} />
          </div>
        </aside>
      </div>
      <footer className="border-t border-rule px-6 py-2.5 text-center text-[11px] text-ink3">
        {t('Keep this pass with your records. Quote the tracking number for anything about this pack.')}
      </footer>
    </article>
  );
}

function PassField({
  label,
  value,
  mono,
  strong,
}: {
  label: string;
  value?: string | null;
  mono?: boolean;
  strong?: boolean;
}) {
  return (
    <div className="min-w-0">
      <p className="text-[10px] font-bold tracking-[0.14em] text-ink3 uppercase">{label}</p>
      <p className={`mt-0.5 text-sm break-words ${mono ? 'font-mono' : ''} ${strong ? 'font-semibold' : ''}`}>
        {value || '—'}
      </p>
    </div>
  );
}

function Signature({ label }: { label: string }) {
  return (
    <div>
      <div className="h-8 border-b border-ink/40" />
      <p className="mt-1 text-center text-[10px] text-ink3">{label}</p>
    </div>
  );
}
