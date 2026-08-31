import { useEffect } from 'react';
import { useParams, Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useReferralCardBatch, type ReferralCard } from '../api/onboarding';
import { ErrorBanner, Spinner } from '../components/ui';

/**
 * Referral cards, laid out for a dot matrix printer.
 *
 * <h2>Why this page looks like 1985</h2>
 *
 * It is printed on an impact printer, and that constrains everything:
 *
 * - **Monospace, 64 columns.** A dot matrix has a fixed character cell. Proportional type is
 *   rendered as a bitmap — slow, and ugly at draft quality — while monospace prints as characters
 *   and comes out crisp. 64 columns fits comfortably inside 80-column tractor paper with margins.
 * - **No colour, no shading, no borders drawn as CSS.** Every dark pixel is a physical pin strike.
 *   A shaded panel is slow, loud, and wears the ribbon out. Rules are drawn with `=` and `-`,
 *   which cost one strike per character like any other glyph.
 * - **Page break between cards**, so each card tears off as its own sheet.
 *
 * The screen version is deliberately the same layout rather than a prettier one. What you see
 * before pressing Print is what comes out, which is the only way to check alignment without
 * wasting paper.
 */
export function ReferralCardsPrintPage() {
  const { t } = useTranslation();
  const { batchId } = useParams<{ batchId: string }>();
  const { data, isLoading, error, refetch } = useReferralCardBatch(batchId ?? null);

  // The title becomes the header a browser prints and the default filename if somebody prints to
  // PDF. "MLM Sittu" beats "localhost:8081/referral-cards/..." on a page handed to a customer.
  useEffect(() => {
    if (!data) return;
    const previous = document.title;
    document.title = `Referral cards - ${data.parentBusinessId ?? ''}`;
    return () => {
      document.title = previous;
    };
  }, [data]);

  if (isLoading) return <Spinner />;
  if (error) return <ErrorBanner error={error} onRetry={() => void refetch()} />;
  if (!data) return null;

  const cards = data.cards ?? [];
  const issued = data.issuedAt ? new Date(data.issuedAt).toLocaleDateString() : '';

  return (
    <>
      {/* Scoped to this page. Everything here is about the printer, not the design system. */}
      <style>{`
        .dm { font-family: "Courier New", Courier, monospace; color: #000; background: #fff; }
        .dm-card {
          white-space: pre;
          font-size: 13px;
          line-height: 1.45;
          letter-spacing: 0.02em;
        }
        @media print {
          /* Nothing but the cards. The rail, the header and the buttons are all screen furniture. */
          body * { visibility: hidden; }
          .dm, .dm * { visibility: visible; }
          .dm { position: absolute; left: 0; top: 0; width: 100%; }
          .dm-noprint { display: none !important; }
          .dm-card {
            /* One card per sheet, so they tear off individually. The last one must not emit a
               trailing blank page, which is what break-after: auto on :last-child avoids. */
            break-after: page;
            page-break-after: always;
            font-size: 12pt;
          }
          .dm-card:last-child { break-after: auto; page-break-after: auto; }
          @page { margin: 12mm; }
        }
      `}</style>

      <div className="dm-noprint mb-5 flex flex-wrap items-center gap-3">
        <button
          type="button"
          onClick={() => window.print()}
          className="rounded-md bg-brand px-4 py-2 text-sm font-semibold text-brandink"
        >
          {t('Print')}
        </button>
        <Link
          to={`/distributors/${data.distributorId}`}
          className="text-sm text-brand underline"
        >
          {t('Back to the customer')}
        </Link>
        <p className="text-xs text-ink3">
          {t(
            'Set the printer to draft quality and plain text. Check one card before running the whole batch.',
          )}
        </p>
      </div>

      <div className="dm">
        {cards.map((card: ReferralCard) => (
          <pre key={card.id} className="dm-card">
{renderCard({
  cardNumber: card.cardNumber ?? 0,
  total: 5,
  code: card.code ?? '',
  parentName: data.parentName ?? '',
  parentBusinessId: data.parentBusinessId ?? '',
  packName: data.itemSetName ?? null,
  packPrice: data.itemSetPrice ?? null,
  issued,
})}
          </pre>
        ))}
      </div>
    </>
  );
}

const WIDTH = 64;

/**
 * One card as fixed-width text.
 *
 * Built as a string rather than as elements on purpose: the alignment *is* the layout here, and
 * padding characters is the only way to be certain a column lines up on a device with no concept
 * of CSS. It also makes the whole card inspectable in one place.
 */
function renderCard(card: {
  /** The seat under the parent, 1-5. Also the last digit of the child ID. */
  cardNumber: number;
  /** Seats in total, not cards in this batch. */
  total: number;
  code: string;
  parentName: string;
  parentBusinessId: string;
  packName: string | null;
  packPrice: number | null;
  issued: string;
}): string {
  const rule = '='.repeat(WIDTH);
  const thin = '-'.repeat(WIDTH);

  // Label column fixed at 11 so every value starts in the same place down the card. Wide enough
  // for "PARENT ID" and "CHILD ID" to sit above one another and still line up.
  const field = (label: string, value: string) => ` ${label.padEnd(11)}${value}`;

  const lines: string[] = [
    rule,
    // The seat, not a position in the batch. A batch printed when seats 1 and 2 are already
    // filled contains seats 3, 4 and 5 — and "card 1 of 3" would contradict the ID printed below.
    spread(' MLM SITTU  -  REFERRAL CARD', `SEAT ${card.cardNumber} OF ${card.total} `),
    rule,
    '',
    // Parent first, then the child. Two identifiers on one small card is exactly where a person
    // in a hurry picks the wrong one, so neither is labelled just "ID".
    field('PARENT', truncate(card.parentName, WIDTH - 13)),
    field('PARENT ID', card.parentBusinessId),
    '',
    field('CHILD ID', card.code),
  ];

  if (card.packName) {
    lines.push('');
    lines.push(field('PACK', truncate(card.packName, WIDTH - 13)));
    if (card.packPrice != null) {
      lines.push(field('PRICE', `LKR ${formatAmount(card.packPrice)}`));
    }
  }

  lines.push(
    '',
    thin,
    ' TO JOIN',
    '',
    // The instruction is the whole point of the card. Somebody who has never seen the system
    // needs to know what to type and where, without asking the person who handed it over.
    '   1. Go to the customer portal and create an account.',
    '   2. Enter the PARENT ID above when it asks who',
    '      referred you.',
    '   3. Upload your NIC and the payment slip.',
    '   4. Give the CHILD ID above to the office, so they',
    '      know which card you were given.',
    '',
    thin,
    spread(` Issued ${card.issued}`, 'Keep this card '),
    rule,
  );

  return lines.join('\n');
}

/** Left text and right text on one line, padded to the full width. */
function spread(left: string, right: string): string {
  const gap = Math.max(1, WIDTH - left.length - right.length);
  return left + ' '.repeat(gap) + right;
}

/** ASCII only. Everything printed here has to survive a printer in draft mode. */
function truncate(value: string, max: number): string {
  return value.length <= max ? value : value.slice(0, max - 3) + '...';
}

/** Grouped thousands, no currency symbol — "LKR" is already printed beside it. */
function formatAmount(amount: number): string {
  return amount.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}
