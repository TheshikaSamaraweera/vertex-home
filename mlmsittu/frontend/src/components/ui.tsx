import type { ReactNode, ButtonHTMLAttributes, InputHTMLAttributes, SelectHTMLAttributes } from 'react';
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
    'inline-flex items-center justify-center gap-2 rounded-md font-medium ' +
    'transition-[background-color,border-color,color,box-shadow] duration-150 ' +
    'active:translate-y-px disabled:opacity-45 disabled:cursor-not-allowed ' +
    'disabled:active:translate-y-0 whitespace-nowrap';

  const sizes = { sm: 'px-2.5 py-1.5 text-xs', md: 'px-3.5 py-2 text-sm' };

  // Hover darkens the fill rather than fading it. Reducing opacity lets the page show through,
  // which on a green button over a green-tinted ground reads as a rendering fault.
  const variants = {
    primary: 'bg-brand text-brandink shadow-sm hover:bg-branddeep',
    secondary: 'bg-panel text-ink border border-rule hover:border-brand hover:text-brand',
    danger: 'bg-dangersoft text-danger border border-danger hover:bg-danger hover:text-panel',
    ghost: 'text-ink2 hover:text-brand hover:bg-brandsoft',
  };
  return <button className={cx(base, sizes[size], variants[variant], className)} {...props} />;
}

// ---------------------------------------------------------------- form fields

export function Field({
  label,
  hint,
  error,
  children,
}: {
  label: string;
  hint?: string;
  error?: string;
  children: ReactNode;
}) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-xs font-semibold tracking-wide text-ink2 uppercase">{label}</span>
      {children}
      {hint && !error && <span className="text-xs text-ink3">{hint}</span>}
      {/* Field errors carry the backend's machine code so a tester can match it to the API
          contract without opening devtools. */}
      {error && <span className="text-xs text-danger">{error}</span>}
    </label>
  );
}

const inputClass =
  'w-full rounded-md border border-rule bg-panel px-3 py-2 text-sm text-ink ' +
  'transition-colors placeholder:text-ink3 ' +
  // A ring as well as a border: a 1px colour change alone is easy to miss, and this is a
  // data-entry app where knowing which field has focus matters more than it looks.
  'focus:border-brand focus:ring-2 focus:ring-brandsoft focus:outline-none ' +
  'disabled:bg-panel2 disabled:text-ink3';

export function Input({ className, ...props }: InputHTMLAttributes<HTMLInputElement>) {
  return <input className={cx(inputClass, className)} {...props} />;
}

export function Select({ className, children, ...props }: SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <select className={cx(inputClass, className)} {...props}>
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
    <section
      className={cx('rounded-xl border border-rule bg-panel shadow-card', className)}
    >
      {(title || actions) && (
        <header className="flex flex-wrap items-center justify-between gap-3 border-b border-rule px-5 py-3.5">
          <div>
            {title && <h2 className="text-sm font-semibold text-ink">{title}</h2>}
            {subtitle && <p className="mt-0.5 text-xs text-ink3">{subtitle}</p>}
          </div>
          {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
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
    <header className="mb-6 flex flex-wrap items-end justify-between gap-3 border-b border-rule pb-4">
      <div>
        <h1 className="text-[22px] leading-tight font-bold tracking-tight text-ink">{title}</h1>
        {description && <p className="mt-1.5 max-w-2xl text-sm text-ink2">{description}</p>}
      </div>
      {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
    </header>
  );
}

// ---------------------------------------------------------------- table

/** Wide tables scroll inside their own container so the page body never scrolls sideways. */
export function TableWrap({ children }: { children: ReactNode }) {
  return <div className="overflow-x-auto">{children}</div>;
}

export function Table({ children }: { children: ReactNode }) {
  return <table className="w-full min-w-[36rem] border-collapse text-sm">{children}</table>;
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
        'border-b border-rule bg-panel2 px-3 py-2.5 text-[10px] font-semibold tracking-wider text-ink2 uppercase',
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
        'border-b border-rule px-3 py-2.5 text-ink2',
        align === 'right' ? 'nums text-right' : '',
        className,
      )}
    >
      {children}
    </td>
  );
}

// ---------------------------------------------------------------- status

type Tone = 'neutral' | 'brand' | 'ok' | 'warn' | 'danger';

const toneClasses: Record<Tone, string> = {
  neutral: 'bg-panel2 text-ink2 border-rule',
  brand: 'bg-brandsoft text-brand border-brand',
  ok: 'bg-oksoft text-ok border-ok',
  warn: 'bg-warnsoft text-warn border-warn',
  danger: 'bg-dangersoft text-danger border-danger',
};

/** State encoded in form as well as text, so a row's condition reads at a glance. */
export function Badge({ children, tone = 'neutral' }: { children: ReactNode; tone?: Tone }) {
  return (
    <span
      className={cx(
        'inline-flex items-center rounded-full border px-2 py-0.5 text-[11px] font-medium whitespace-nowrap',
        toneClasses[tone],
      )}
    >
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
      ? { classes: 'bg-oksoft text-ok border-ok', label: 'in stock' }
      : available >= 5
        ? { classes: 'bg-warnsoft text-warn border-warn', label: 'getting low' }
        : { classes: 'bg-dangersoft text-danger border-danger', label: 'nearly out' };

  return (
    <span
      title={`${available} available — ${band.label}`}
      className={cx(
        'nums inline-flex items-center justify-center rounded border font-semibold',
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

/** Money, always two decimals and always right-aligned by the caller. */
export function money(value: unknown): string {
  return Number(value ?? 0).toFixed(2);
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
    <div className="rounded-md border border-danger bg-dangersoft px-4 py-3 text-sm text-ink">
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
    <div className="px-4 py-10 text-center">
      <p className="text-sm text-ink2">{message}</p>
      {hint && <p className="mt-1 text-xs text-ink3">{hint}</p>}
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
      className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-ink/35 p-4 backdrop-blur-[2px] sm:p-8"
      role="dialog"
      aria-modal="true"
      aria-label={title}
      onClick={onClose}
    >
      <div
        className={cx(
          'w-full rounded-xl border border-rule bg-panel shadow-float',
          wide ? 'max-w-3xl' : 'max-w-lg',
        )}
        onClick={(event) => event.stopPropagation()}
      >
        <header className="flex items-center justify-between border-b border-rule px-5 py-3.5">
          <h2 className="text-sm font-semibold text-ink">{title}</h2>
          <Button size="sm" variant="ghost" onClick={onClose} aria-label="Close">
            ✕
          </Button>
        </header>
        <div className="p-5">{children}</div>
      </div>
    </div>
  );
}
