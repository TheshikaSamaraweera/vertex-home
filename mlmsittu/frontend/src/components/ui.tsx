import { useState } from 'react';
import type { ReactNode, ButtonHTMLAttributes, InputHTMLAttributes, SelectHTMLAttributes } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiError, NetworkError } from '../api/client';

/**
 * A small set of primitives, hand-written rather than vendored from shadcn/ui.
 *
 * The architecture names shadcn, which is itself "copy the source into your repo" — the value is
 * owning the components, not the specific source. This is a dozen components with no Radix
 * dependency, styled from the same tokens as the project's documentation.
 */

function cx(...parts: Array<string | false | null | undefined>): string {
  return parts.filter(Boolean).join(' ');
}

// ---------------------------------------------------------------- button

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: 'primary' | 'secondary' | 'danger' | 'ghost';
  size?: 'sm' | 'md';
};

export function Button({ variant = 'secondary', size = 'md', className, ...props }: ButtonProps) {
  const base =
    'inline-flex items-center justify-center gap-1.5 rounded-lg font-semibold ' +
    'transition-[background-color,border-color,color,box-shadow] duration-150 ' +
    'active:translate-y-px disabled:opacity-45 disabled:cursor-not-allowed ' +
    'disabled:active:translate-y-0 whitespace-nowrap';

  // Fixed heights rather than padding alone, so a button sits level with the 40px inputs beside
  // it in a filter bar instead of a few pixels short.
  const sizes = { sm: 'h-8 px-3 text-xs', md: 'h-10 px-4 text-sm' };

  // Hover darkens the fill rather than fading it. Reducing opacity lets the card show through,
  // which reads as a rendering fault rather than as a state.
  const variants = {
    primary: 'bg-brand text-brandink shadow-sm hover:bg-branddeep',
    secondary:
      'bg-panel text-ink border border-rulestrong shadow-xs hover:border-brand/40 hover:bg-brandsoft hover:text-brand',
    // Red at rest, not only on hover: a destructive action should be recognisable before the
    // pointer reaches it. Solid red on hover confirms what is about to happen.
    danger:
      'bg-dangersoft text-danger border border-danger/25 hover:bg-danger hover:border-danger hover:text-white',
    ghost: 'text-ink2 hover:text-brand hover:bg-brandsoft',
  };
  return <button className={cx(base, sizes[size], variants[variant], className)} {...props} />;
}

// ---------------------------------------------------------------- form fields

export function Field({
  label,
  hint,
  error,
  required,
  children,
}: {
  label: string;
  hint?: string;
  error?: string;
  /** Marks the field as required, which draws the red asterisk. */
  required?: boolean;
  children: ReactNode;
}) {
  return (
    <label className="flex flex-col gap-1.5">
      {/* Sentence case at 13px, not uppercase captions: a long form of shouting labels is harder
          to scan, and the field outlines already do the job the capitals were doing. */}
      <span className="text-[13px] font-medium text-ink">
        {label}
        {required && (
          // Marked on the label rather than left to the browser's own validation, which says
          // nothing until the form is submitted. Somebody filling in a long form should be able
          // to see what they must answer before they start.
          //
          // aria-hidden because the asterisk is a visual convention, not something a screen reader
          // should read as "star" — the input's own `required` attribute is what it announces.
          <span aria-hidden className="ml-0.5 text-danger">
            *
          </span>
        )}
      </span>
      {children}
      {hint && !error && <span className="text-xs text-ink3">{hint}</span>}
      {/* Field errors carry the backend's machine code so a tester can match it to the API
          contract without opening devtools. */}
      {error && (
        <span role="alert" className="flex items-start gap-1 text-xs text-danger">
          <span aria-hidden>⚠</span>
          <span>{error}</span>
        </span>
      )}
    </label>
  );
}

/**
 * The red line down the left of a block of instructions.
 *
 * <p>For the rules somebody has to follow to fill a section in — what a Business ID looks like,
 * what an upload must be, which of two fields is required. A paragraph of grey text above a form
 * is read by nobody; the same words against a coloured rule are read because they look like they
 * are worth reading.
 *
 * <p>Not for errors. An error is about what just happened and belongs on the field that caused it;
 * this is about what to do, and it is there before anything has gone wrong.
 */
export function Instructions({
  title,
  children,
  tone = 'danger',
}: {
  title?: string;
  children: ReactNode;
  tone?: 'danger' | 'brand';
}) {
  const line = tone === 'danger' ? 'border-l-danger' : 'border-l-brand';
  return (
    <div className={`rounded-r-lg border-l-4 bg-panel2 px-4 py-3 ${line}`}>
      {title && <p className="text-sm font-semibold text-ink">{title}</p>}
      <div className={`text-xs text-ink2 ${title ? 'mt-1' : ''}`}>{children}</div>
    </div>
  );
}

const inputClass =
  'h-10 rounded-lg border border-rulestrong bg-panel px-3 text-sm text-ink shadow-xs ' +
  'transition-[border-color,box-shadow] placeholder:text-ink3 ' +
  // A ring as well as a border: a 1px colour change alone is easy to miss, and this is a
  // data-entry app where knowing which field has focus matters more than it looks.
  'focus:border-brand focus:ring-4 focus:ring-brandsoft focus:outline-none ' +
  'disabled:bg-panel2 disabled:text-ink3';

/**
 * Red border and red focus ring when a field is wrong.
 *
 * <p>Applied through `aria-invalid` rather than a prop, so the thing that tells a screen reader
 * the field is wrong is the same thing that colours it. The two cannot drift apart, and marking a
 * field red without marking it invalid — which is the usual way this is written — leaves anybody
 * not looking at the colour with no idea anything is wrong.
 */
const invalidClass =
  'aria-[invalid=true]:border-danger aria-[invalid=true]:focus:border-danger ' +
  'aria-[invalid=true]:focus:ring-dangersoft';

/**
 * Full width unless the caller sizes it.
 *
 * Both classes in one list do not compose: `w-full w-64` resolves by stylesheet order, not by the
 * order they are written, and full width won — so every filter box that asked for 16rem stretched
 * across its card. Adding the default only when no width was given makes the caller's choice
 * actually stick.
 */
function widthOf(className: string | undefined): string {
  return /(^|\s)(w-|flex-1|min-w-0)/.test(className ?? '') ? '' : 'w-full';
}

export function Input({ className, ...props }: InputHTMLAttributes<HTMLInputElement>) {
  return <input className={cx(inputClass, widthOf(className), invalidClass, className)} {...props} />;
}

/**
 * A password field with a show / hide toggle.
 *
 * Typing a password blind on a phone keyboard is how people get locked out: one wrong character
 * and there is no way to see which. The toggle is a real button — reachable by keyboard, announced
 * with its state — and `type="button"` so pressing it never submits the form it sits in.
 *
 * Starts hidden every time. Remembering "shown" would leave a password on screen the next time
 * somebody opens the form, possibly in front of someone else.
 */
export function PasswordInput({
  className,
  ...props
}: Omit<InputHTMLAttributes<HTMLInputElement>, 'type'>) {
  const { t } = useTranslation();
  const [visible, setVisible] = useState(false);
  const label = visible ? t('Hide password') : t('Show password');

  return (
    <div className="relative">
      <input
        {...props}
        type={visible ? 'text' : 'password'}
        className={cx(inputClass, 'w-full', invalidClass, 'pr-10', className)}
      />
      <button
        type="button"
        onClick={() => setVisible((shown) => !shown)}
        aria-label={label}
        aria-pressed={visible}
        title={label}
        className="absolute inset-y-0 right-0 flex w-10 items-center justify-center rounded-r-lg text-ink3 hover:text-brand focus-visible:text-brand focus-visible:outline-none"
      >
        <svg
          aria-hidden
          viewBox="0 0 24 24"
          className="h-4 w-4"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
        >
          <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z" />
          <circle cx="12" cy="12" r="3" />
          {/* The slash says "currently shown — press to hide", matching the label. */}
          {visible && <path d="M3 3l18 18" />}
        </svg>
      </button>
    </div>
  );
}

export function Select({ className, children, ...props }: SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <select className={cx(inputClass, widthOf(className), invalidClass, className)} {...props}>
      {children}
    </select>
  );
}

// ---------------------------------------------------------------- layout

export function Card({
  title,
  subtitle,
  actions,
  children,
  className,
}: {
  title?: string;
  subtitle?: string;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    // Lifted off the canvas by a soft shadow and a hairline, not outlined. A heavy border on every
    // card turned each screen into a grid of boxes competing for attention; a card only has to say
    // "this is one thing", and the shadow says it quietly.
    <section
      className={cx('rounded-2xl border border-rule bg-panel shadow-card', className)}
    >
      {(title || actions) && (
        <header className="flex flex-wrap items-center justify-between gap-3 border-b border-rule px-5 py-4">
          <div className="min-w-0">
            {title && <h2 className="text-[15px] font-semibold text-ink">{title}</h2>}
            {subtitle && <p className="mt-0.5 text-[13px] text-ink3">{subtitle}</p>}
          </div>
          {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
        </header>
      )}
      {children}
    </section>
  );
}

export function PageHeader({
  title,
  description,
  actions,
}: {
  title: string;
  description?: string;
  actions?: ReactNode;
}) {
  return (
    // No rule under it. The cards below start where the header ends, and on a grey canvas the
    // change from text to white card is already the boundary.
    <header className="mb-6 flex flex-wrap items-end justify-between gap-4">
      <div className="min-w-0">
        <h1 className="text-2xl leading-tight font-bold tracking-tight text-ink">{title}</h1>
        {description && <p className="mt-1.5 max-w-2xl text-sm text-ink3">{description}</p>}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </header>
  );
}

// ---------------------------------------------------------------- table

/** Wide tables scroll inside their own container so the page body never scrolls sideways. */
export function TableWrap({ children }: { children: ReactNode }) {
  return <div className="overflow-x-auto">{children}</div>;
}

export function Table({ children }: { children: ReactNode }) {
  return (
    // The row hover is set here, on a descendant selector, rather than on a Tr component every
    // table would have to adopt. Twenty pages build their own rows; one of them would have been
    // missed, and a table where hovering does nothing reads as broken rather than as plain.
    //
    // It earns its place on a wide table: it is what keeps the eye on one record while it travels
    // from the first column to the last. Without it, reading a figure at the right-hand edge and
    // knowing whose figure it is are two separate acts.
    // The last row drops its divider: the card edge is already there, and a line right above it
    // reads as a double rule.
    <table className="w-full min-w-[36rem] border-collapse text-sm [&_tbody_tr]:transition-colors [&_tbody_tr:hover]:bg-panel2 [&_tbody_tr:last-child_td]:border-b-0">
      {children}
    </table>
  );
}

export function Th({
  children,
  align = 'left',
}: {
  children: ReactNode;
  align?: 'left' | 'right';
}) {
  return (
    <th
      className={cx(
        // A tinted band with small capitals. The band, not the weight of the type, is what
        // separates labels from values, so the labels can stay quiet and let the data lead.
        'border-b border-rule bg-panel2 px-4 py-3 text-[11px] font-semibold tracking-[0.06em] whitespace-nowrap text-ink3 uppercase',
        align === 'right' ? 'text-right' : 'text-left',
      )}
    >
      {children}
    </th>
  );
}

export function Td({
  children,
  align = 'left',
  className,
}: {
  children: ReactNode;
  align?: 'left' | 'right';
  className?: string;
}) {
  return (
    <td
      className={cx(
        // Hairline dividers and generous row height: dense enough for a stock list, airy enough
        // that a row is easy to follow across a wide table.
        'border-b border-rule px-4 py-3 text-ink2',
        align === 'right' ? 'nums text-right' : '',
        className,
      )}
    >
      {children}
    </td>
  );
}

/**
 * Previous / Next under a paged table.
 *
 * No page count and no jumping to page 7: the server pages by cursor, which is what keeps a page
 * exact while other people are adding rows, and a cursor only knows what comes next. Hidden
 * entirely when everything fits on one page — a control that can do nothing is just noise.
 */
export function Pager({
  page,
  hasNext,
  onPrevious,
  onNext,
  loading,
}: {
  /** 1-based. */
  page: number;
  hasNext: boolean;
  onPrevious: () => void;
  onNext: () => void;
  /** The next page is on its way; both buttons wait for it. */
  loading?: boolean;
}) {
  const { t } = useTranslation();
  if (page === 1 && !hasNext) return null;

  return (
    <nav
      aria-label={t('Pages')}
      className="flex items-center justify-between gap-3 border-t border-rule px-5 py-3"
    >
      <span className="nums text-[13px] font-medium text-ink3">{t('Page {{page}}', { page })}</span>
      <div className="flex gap-2">
        <Button size="sm" onClick={onPrevious} disabled={page === 1 || loading}>
          <Chevron direction="left" />
          {t('Previous')}
        </Button>
        <Button size="sm" onClick={onNext} disabled={!hasNext || loading}>
          {t('Next')}
          <Chevron direction="right" />
        </Button>
      </div>
    </nav>
  );
}

function Chevron({ direction }: { direction: 'left' | 'right' }) {
  return (
    <svg aria-hidden viewBox="0 0 24 24" className="h-3.5 w-3.5" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
      <path d={direction === 'left' ? 'M15 18l-6-6 6-6' : 'M9 18l6-6-6-6'} />
    </svg>
  );
}

// ---------------------------------------------------------------- status

type Tone = 'neutral' | 'brand' | 'ok' | 'warn' | 'danger';

const toneClasses: Record<Tone, string> = {
  neutral: 'bg-[#eef1f5] text-ink2',
  brand: 'bg-brandsoft text-brand',
  ok: 'bg-oksoft text-ok',
  warn: 'bg-warnsoft text-warn',
  danger: 'bg-dangersoft text-danger',
};

/**
 * State encoded in form as well as text, so a row's condition reads at a glance.
 *
 * A soft pill with a dot in the tone's own colour. The dot is what survives a greyscale print or
 * a colour-blind reader skimming a column: it marks "this is a status" before the word is read.
 */
export function Badge({ children, tone = 'neutral' }: { children: ReactNode; tone?: Tone }) {
  return (
    <span
      className={cx(
        'inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-medium whitespace-nowrap',
        toneClasses[tone],
      )}
    >
      <span aria-hidden className="h-1.5 w-1.5 flex-none rounded-full bg-current opacity-80" />
      {children}
    </span>
  );
}

export function statusTone(status: string): Tone {
  switch (status) {
    case 'active':
    case 'received':
    case 'verified':
    case 'fulfilled':
    case 'consumed':
      return 'ok';
    case 'draft':
      return 'neutral';
    case 'sent':
    case 'partially_received':
    case 'payment_review':
    case 'paid':
      return 'brand';
    // Waiting on a human. Amber, because these are the rows somebody has to act on.
    case 'awaiting_payment':
    case 'pending':
    // Signed for, still on the receiving bay. Amber for the same reason: somebody has to put it
    // away before the stock figures mean anything.
    case 'arrived':
      return 'warn';
    case 'released':
    case 'expired':
    case 'cancelled':
    case 'suspended':
    case 'rejected':
    case 'payment_rejected':
      return 'danger';
    default:
      return 'neutral';
  }
}

/**
 * Available stock as a small coloured box.
 *
 * Three bands, at the client's thresholds:
 *
 * | Available | Reads as | Colour |
 * |---|---|---|
 * | more than 10 | comfortable | green |
 * | 5 to 10 | getting low | amber |
 * | under 5 | nearly out | red |
 *
 * The boundaries were given as "> 10", "10 > q > 5" and "< 5", which leaves 5 and 10 themselves
 * unstated. They are put in the amber band, because the alternative is a quantity with no colour
 * at all — and of the two ways to be wrong about a boundary, calling a 10 "getting low" is the one
 * that does no harm.
 *
 * Colour is never the only signal: the number is always there beside it, and the box carries a
 * title. A colour-blind reader loses nothing.
 */
export function AvailabilityBox({
  available,
  size = 'md',
}: {
  available: number | null | undefined;
  size?: 'sm' | 'md';
}) {
  if (available == null) {
    return <span className="text-ink3">—</span>;
  }

  const band =
    available > 10
      ? { classes: 'bg-oksoft text-ok', label: 'in stock' }
      : available >= 5
        ? { classes: 'bg-warnsoft text-warn', label: 'getting low' }
        : { classes: 'bg-dangersoft text-danger', label: 'nearly out' };

  return (
    <span
      title={`${available} available — ${band.label}`}
      className={cx(
        'nums inline-flex items-center justify-center rounded-md font-semibold',
        size === 'sm' ? 'min-w-9 px-1.5 py-0.5 text-[11px]' : 'min-w-11 px-2 py-0.5 text-xs',
        band.classes,
      )}
    >
      {available}
      <span className="sr-only"> available, {band.label}</span>
    </span>
  );
}

/** Status codes are snake_case on the wire; nobody wants to read that in a table cell. */
export function humanStatus(status: string | undefined): string {
  return (status ?? '').replace(/_/g, ' ');
}

/**
 * Money, always two decimals and always right-aligned by the caller.
 *
 * Grouped into thousands: "124,500.00" is read at a glance where "124500.00" has to be counted.
 * Fixed to en-US grouping rather than the browser's locale, so a figure reads the same on every
 * machine that looks at it — the same reason the app has one colour scheme.
 */
const moneyFormat = new Intl.NumberFormat('en-US', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

export function money(value: unknown): string {
  return moneyFormat.format(Number(value ?? 0));
}

// ---------------------------------------------------------------- feedback

/**
 * Renders any thrown error the same way, and always surfaces the machine code.
 *
 * Showing the code is a deliberate choice for an internal tool: when a clerk reports a problem,
 * "it said INSUFFICIENT_STOCK" is a fact you can act on, where "it didn't work" is not.
 */
export function ErrorBanner({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  if (!error) return null;

  let code = 'UNKNOWN';
  let detail = 'Something went wrong.';
  let extras: Array<[string, unknown]> = [];

  if (error instanceof ApiError) {
    code = error.code;
    detail = error.detail;
    extras = Object.entries(error.properties).filter(([key]) => key !== 'errors');
  } else if (error instanceof NetworkError) {
    code = 'NETWORK';
    detail = error.message;
  } else if (error instanceof Error) {
    detail = error.message;
  }

  return (
    <div className="rounded-xl border border-danger/25 bg-dangersoft px-4 py-3 text-sm text-ink">
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-mono text-[11px] font-bold tracking-wider text-danger uppercase">
          {code}
        </span>
        <span>{detail}</span>
        {onRetry && (
          <Button size="sm" variant="ghost" onClick={onRetry}>
            Try again
          </Button>
        )}
      </div>
      {extras.length > 0 && (
        <dl className="nums mt-2 flex flex-wrap gap-x-4 gap-y-1 text-xs text-ink2">
          {extras.map(([key, value]) => (
            <div key={key} className="flex gap-1">
              <dt className="text-ink3">{key}:</dt>
              <dd className="font-mono">{String(value)}</dd>
            </div>
          ))}
        </dl>
      )}
    </div>
  );
}

export function Spinner({ label }: { label?: string }) {
  return (
    <div className="flex items-center gap-2 px-4 py-8 text-sm text-ink3">
      <span
        className="h-4 w-4 animate-spin rounded-full border-2 border-rule border-t-brand"
        aria-hidden
      />
      {label ?? 'Loading…'}
    </div>
  );
}

export function EmptyState({ message, hint }: { message: string; hint?: string }) {
  return (
    <div className="flex flex-col items-center px-4 py-12 text-center">
      {/* A soft tile rather than a bare sentence, so an empty table reads as "nothing here yet"
          instead of "something failed to render". */}
      <span
        aria-hidden
        className="mb-3 flex h-12 w-12 items-center justify-center rounded-2xl bg-panel2 text-ink3"
      >
        <svg viewBox="0 0 24 24" className="h-6 w-6" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round">
          <path d="M21 8v13H3V8" />
          <path d="M1 3h22v5H1z" />
          <path d="M10 12h4" />
        </svg>
      </span>
      <p className="text-sm font-medium text-ink">{message}</p>
      {hint && <p className="mt-1 max-w-sm text-[13px] text-ink3">{hint}</p>}
    </div>
  );
}

// ---------------------------------------------------------------- modal

export function Modal({
  title,
  onClose,
  children,
  wide,
}: {
  title: string;
  onClose: () => void;
  children: ReactNode;
  wide?: boolean;
}) {
  return (
    <div
      className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-ink/40 p-4 backdrop-blur-[3px] sm:p-8"
      role="dialog"
      aria-modal="true"
      aria-label={title}
      onClick={onClose}
    >
      <div
        className={cx(
          'w-full rounded-2xl border border-rule bg-panel shadow-float',
          wide ? 'max-w-3xl' : 'max-w-lg',
        )}
        onClick={(event) => event.stopPropagation()}
      >
        <header className="flex items-center justify-between gap-3 border-b border-rule px-6 py-4">
          <h2 className="text-base font-semibold text-ink">{title}</h2>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="flex h-8 w-8 items-center justify-center rounded-lg text-ink3 transition-colors hover:bg-panel2 hover:text-ink"
          >
            <svg aria-hidden viewBox="0 0 24 24" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="2.25" strokeLinecap="round">
              <path d="M18 6 6 18M6 6l12 12" />
            </svg>
          </button>
        </header>
        <div className="p-6">{children}</div>
      </div>
    </div>
  );
}
