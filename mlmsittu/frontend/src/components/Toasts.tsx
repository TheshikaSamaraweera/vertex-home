import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';

/**
 * Transient messages, stacked in a corner.
 *
 * <p>A toast is for something that happened **elsewhere** — a notification arriving while you were
 * on another screen. It is deliberately not used for the result of your own action: a form that
 * saved should say so where the form is, not in the corner, because a person looking at the field
 * they just filled in should not have to look away to learn whether it worked.
 */

export type Toast = {
  id: string;
  title: string;
  body?: string | null;
  link?: string | null;
  tone?: 'info' | 'ok' | 'danger';
};

type ToastContextValue = { show: (toast: Omit<Toast, 'id'>) => void };

const ToastContext = createContext<ToastContextValue>({ show: () => {} });

export const useToasts = () => useContext(ToastContext);

/** How long a toast stays before it fades. Long enough to read two lines without hurrying. */
const DISMISS_AFTER_MS = 8000;

/** Beyond this the stack covers the screen, so the oldest go. */
const MAX_VISIBLE = 4;

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);

  const dismiss = useCallback(
    (id: string) => setToasts((current) => current.filter((toast) => toast.id !== id)),
    [],
  );

  const show = useCallback((toast: Omit<Toast, 'id'>) => {
    const id = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    setToasts((current) => [...current, { ...toast, id }].slice(-MAX_VISIBLE));
  }, []);

  const value = useMemo(() => ({ show }), [show]);

  return (
    <ToastContext.Provider value={value}>
      {children}
      <div
        // aria-live so a screen reader announces arrivals; "polite" because a notification is
        // never urgent enough to interrupt whatever is being read.
        aria-live="polite"
        aria-atomic="false"
        className="pointer-events-none fixed right-4 bottom-4 z-50 flex w-[min(22rem,calc(100vw-2rem))] flex-col gap-2"
      >
        {toasts.map((toast) => (
          <ToastCard key={toast.id} toast={toast} onDismiss={() => dismiss(toast.id)} />
        ))}
      </div>
    </ToastContext.Provider>
  );
}

function ToastCard({ toast, onDismiss }: { toast: Toast; onDismiss: () => void }) {
  useEffect(() => {
    const timer = window.setTimeout(onDismiss, DISMISS_AFTER_MS);
    return () => window.clearTimeout(timer);
  }, [onDismiss]);

  const tone =
    toast.tone === 'ok'
      ? 'border-ok bg-oksoft'
      : toast.tone === 'danger'
        ? 'border-danger bg-dangersoft'
        : 'border-brand bg-panel';

  const content = (
    <>
      <p className="text-sm font-semibold text-ink">{toast.title}</p>
      {toast.body && <p className="mt-0.5 text-xs text-ink2">{toast.body}</p>}
    </>
  );

  return (
    <div
      className={`pointer-events-auto rounded-lg border-2 px-4 py-3 shadow-lg ${tone}`}
      role="status"
    >
      <div className="flex items-start gap-3">
        <div className="min-w-0 flex-1">
          {toast.link ? (
            <Link to={toast.link} onClick={onDismiss} className="block">
              {content}
            </Link>
          ) : (
            content
          )}
        </div>
        <button
          type="button"
          onClick={onDismiss}
          aria-label="Dismiss"
          className="flex-none text-ink3 hover:text-ink"
        >
          ✕
        </button>
      </div>
    </div>
  );
}
