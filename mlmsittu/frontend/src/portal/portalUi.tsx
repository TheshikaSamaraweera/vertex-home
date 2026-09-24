import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Icon } from '../components/icons';

/**
 * Small pieces the customer portal is built from.
 *
 * The portal is the one screen a customer shows to somebody else — the person they are about to
 * refer — so it is allowed more colour and warmth than the staff app. These keep that consistent.
 */

/** Soft two-tone gradients for avatars, picked by name so a person keeps the same colour. */
const AVATAR_GRADIENTS = [
  'from-[#38b2a3] to-[#0b7a6e]',
  'from-[#7c8cff] to-[#4f5bd5]',
  'from-[#f6a35c] to-[#e0672b]',
  'from-[#f472b6] to-[#c0266d]',
  'from-[#60a5fa] to-[#2563eb]',
  'from-[#a78bfa] to-[#6d28d9]',
  'from-[#34d399] to-[#059669]',
];

function hash(text: string): number {
  let value = 0;
  for (const char of text) value = (value * 31 + char.charCodeAt(0)) | 0;
  return Math.abs(value);
}

export function initialsOf(name: string | null | undefined): string {
  return (
    (name ?? '')
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((part) => part[0]?.toUpperCase())
      .join('') || '?'
  );
}

export function PersonAvatar({
  name,
  size = 'md',
  className = '',
}: {
  name: string | null | undefined;
  size?: 'sm' | 'md' | 'lg' | 'xl';
  className?: string;
}) {
  const gradient = AVATAR_GRADIENTS[hash(name ?? '') % AVATAR_GRADIENTS.length];
  const dimensions = {
    sm: 'h-8 w-8 text-[11px]',
    md: 'h-10 w-10 text-[13px]',
    lg: 'h-14 w-14 text-lg',
    xl: 'h-20 w-20 text-2xl',
  }[size];
  return (
    <span
      aria-hidden
      className={`flex flex-none items-center justify-center rounded-full bg-linear-to-br font-bold text-white shadow-sm ${gradient} ${dimensions} ${className}`}
    >
      {initialsOf(name)}
    </span>
  );
}

/**
 * A circular progress ring with the figure in the middle.
 *
 * Drawn in SVG so it stays crisp at any size; `stroke-dasharray` does the arc, and the track is
 * the same shape at low opacity so an empty ring still reads as a ring.
 */
export function ProgressRing({
  value,
  total,
  size = 132,
  stroke = 12,
  tone = 'light',
  label,
}: {
  value: number;
  total: number;
  size?: number;
  stroke?: number;
  /** `light` for a dark, coloured background; `brand` for a white card. */
  tone?: 'light' | 'brand';
  label?: string;
}) {
  const radius = (size - stroke) / 2;
  const circumference = 2 * Math.PI * radius;
  const fraction = total > 0 ? Math.min(1, value / total) : 0;
  const track = tone === 'light' ? 'rgba(255,255,255,0.22)' : 'var(--c-rule-strong)';
  const arc = tone === 'light' ? '#ffffff' : 'url(#ring-brand)';

  return (
    <div className="relative flex-none" style={{ width: size, height: size }}>
      <svg width={size} height={size} className="-rotate-90" aria-hidden>
        <defs>
          <linearGradient id="ring-brand" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stopColor="var(--c-brand-bright)" />
            <stop offset="100%" stopColor="var(--c-brand)" />
          </linearGradient>
        </defs>
        <circle cx={size / 2} cy={size / 2} r={radius} fill="none" stroke={track} strokeWidth={stroke} />
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          stroke={arc}
          strokeWidth={stroke}
          strokeLinecap="round"
          strokeDasharray={circumference}
          strokeDashoffset={circumference * (1 - fraction)}
          className="transition-[stroke-dashoffset] duration-700 ease-out"
        />
      </svg>
      <div className="absolute inset-0 flex flex-col items-center justify-center">
        <span
          className={`nums text-3xl leading-none font-extrabold ${tone === 'light' ? 'text-white' : 'text-ink'}`}
        >
          {value}
          <span className={`text-base font-semibold ${tone === 'light' ? 'text-white/70' : 'text-ink3'}`}>
            /{total}
          </span>
        </span>
        {label && (
          <span
            className={`mt-1 text-[11px] font-semibold tracking-wide uppercase ${tone === 'light' ? 'text-white/80' : 'text-ink3'}`}
          >
            {label}
          </span>
        )}
      </div>
    </div>
  );
}

/** Copies a value and says so for a moment. */
export function CopyButton({
  value,
  label,
  variant = 'light',
}: {
  value: string;
  label?: string;
  variant?: 'light' | 'glass';
}) {
  const { t } = useTranslation();
  const [copied, setCopied] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(true);
      setTimeout(() => setCopied(false), 1800);
    } catch {
      // Clipboard can be refused (an insecure origin, a denied permission). The value is on
      // screen either way, so failing quietly costs nothing.
    }
  }

  const style =
    variant === 'glass'
      ? 'bg-white/15 text-white ring-1 ring-white/25 hover:bg-white/25'
      : 'bg-white text-ink shadow-sm ring-1 ring-rule hover:text-brand';

  return (
    <button
      type="button"
      onClick={() => void copy()}
      disabled={!value}
      className={`inline-flex h-9 items-center gap-1.5 rounded-full px-3.5 text-xs font-semibold transition-colors disabled:opacity-40 ${style}`}
    >
      <Icon name={copied ? 'check' : 'copy'} className="h-3.5 w-3.5" />
      {copied ? t('Copied') : (label ?? t('Copy'))}
    </button>
  );
}

/** A section heading for the portal pages: small eyebrow, larger title. */
export function SectionTitle({ eyebrow, title }: { eyebrow?: string; title: string }) {
  return (
    <div className="mb-4">
      {eyebrow && (
        <p className="text-[11px] font-bold tracking-[0.12em] text-brand uppercase">{eyebrow}</p>
      )}
      <h2 className="mt-0.5 text-lg font-bold tracking-tight text-ink">{title}</h2>
    </div>
  );
}

/** The portal's white card: rounder and softer than the staff one. */
export function GlassCard({
  children,
  className = '',
}: {
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <section
      className={`rounded-3xl border border-white/70 bg-white/85 p-6 shadow-[0_10px_30px_-12px_rgba(16,24,40,0.18)] backdrop-blur-sm ${className}`}
    >
      {children}
    </section>
  );
}
