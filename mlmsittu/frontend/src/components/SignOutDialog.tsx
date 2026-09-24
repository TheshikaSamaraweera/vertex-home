import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthContext';
import { Icon } from './icons';

/**
 * "Sign out?" — asked before the session ends.
 *
 * Signing out sits beside things people click all day (the account name, the notification bell),
 * and a mis-click used to end the session on the spot: a half-filled registration or an order
 * being keyed in was simply gone. One confirmation costs a click on purpose and saves that.
 *
 * Focus lands on Cancel, not on Sign out, so a stray Enter keeps you where you were; Escape and a
 * click outside also cancel. While the request is in flight both buttons wait, so a double click
 * cannot fire two sign-outs.
 */
export function SignOutDialog({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  const { user, signOut } = useAuth();
  const [busy, setBusy] = useState(false);
  const cancel = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    cancel.current?.focus();
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !busy) onClose();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [busy, onClose]);

  async function confirm() {
    setBusy(true);
    try {
      await signOut();
    } finally {
      // Normally unmounted by then — the app swaps to the sign-in page — but if signing out
      // failed, the dialog must not stay stuck in a waiting state.
      setBusy(false);
      onClose();
    }
  }

  return (
    <div
      className="fixed inset-0 z-[60] flex items-center justify-center bg-ink/45 p-4 backdrop-blur-sm"
      role="alertdialog"
      aria-modal="true"
      aria-labelledby="sign-out-title"
      aria-describedby="sign-out-body"
      onClick={() => !busy && onClose()}
    >
      <div
        className="w-full max-w-sm rounded-2xl border border-rule bg-panel p-6 text-center shadow-float"
        onClick={(event) => event.stopPropagation()}
      >
        <span className="mx-auto flex h-14 w-14 items-center justify-center rounded-2xl bg-dangersoft text-danger">
          <Icon name="logout" className="h-6 w-6" />
        </span>
        <h2 id="sign-out-title" className="mt-4 text-lg font-bold text-ink">
          {t('Sign out?')}
        </h2>
        <p id="sign-out-body" className="mt-1.5 text-sm text-ink3">
          {user?.fullName
            ? t('You are signed in as {{name}}. Anything not yet saved will be lost.', {
                name: user.fullName,
              })
            : t('Anything not yet saved will be lost.')}
        </p>
        <div className="mt-6 grid grid-cols-2 gap-3">
          <button
            ref={cancel}
            type="button"
            disabled={busy}
            onClick={onClose}
            className="h-11 rounded-xl border border-rulestrong bg-panel text-sm font-semibold text-ink transition-colors hover:bg-panel2 disabled:opacity-50"
          >
            {t('Cancel')}
          </button>
          <button
            type="button"
            disabled={busy}
            onClick={() => void confirm()}
            className="h-11 rounded-xl bg-danger text-sm font-semibold text-white shadow-sm transition-colors hover:bg-[#a52c22] disabled:opacity-60"
          >
            {busy ? t('Signing out…') : t('Sign out')}
          </button>
        </div>
      </div>
    </div>
  );
}
