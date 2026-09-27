import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api, ApiError } from '../api/client';
import {
  isAcceptablePassword,
  MAX_LENGTH,
  MIN_LENGTH,
  PASSWORD_RULE,
  passwordProblem,
} from '../lib/password';
import { Button, ErrorBanner, Field, Input, Instructions } from '../components/ui';

/**
 * Forgotten passwords, both halves.
 *
 * <p>Asking for a link and spending one, in a single component, because they are two ends of the
 * same journey and the second is reached by a URL the first sent. Which half renders is decided by
 * whether the address bar carries a token.
 *
 * <p>**Most customers here have no email address**, so the first screen says so plainly rather
 * than leaving somebody typing a phone number into a form that will silently do nothing for them.
 * The office route is the answer for those people and the page names it.
 */
export function ForgotPasswordPage({ onDone }: { onDone: () => void }) {
  const { t } = useTranslation();

  const tokenFromLink = new URLSearchParams(window.location.search).get('token');

  return tokenFromLink ? (
    <Frame title={t('Choose a new password')}>
      <ChooseNewPassword token={tokenFromLink} onDone={onDone} />
    </Frame>
  ) : (
    <Frame title={t('Forgotten password')}>
      <AskForLink onDone={onDone} />
    </Frame>
  );
}

function Frame({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="flex min-h-dvh items-center justify-center bg-ground px-4 py-10">
      <div className="w-full max-w-sm">
        <div className="mb-6">
          <h1 className="text-2xl font-bold tracking-tight text-ink">Vertex Home Solutions</h1>
          <p className="mt-1 text-sm text-ink2">{title}</p>
        </div>
        <div className="rounded-lg border-2 border-rulestrong bg-panel p-5">{children}</div>
      </div>
    </div>
  );
}

function AskForLink({ onDone }: { onDone: () => void }) {
  const { t } = useTranslation();
  const [identifier, setIdentifier] = useState('');
  const [sent, setSent] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.post('/api/v1/auth/forgot-password', { identifier });
      setSent(true);
    } catch (caught) {
      setError(caught);
    } finally {
      setBusy(false);
    }
  }

  if (sent) {
    return (
      <div className="flex flex-col gap-4">
        <div className="rounded border-2 border-ok bg-oksoft px-3 py-2.5">
          <p className="text-sm font-semibold text-ok">{t('Check your email')}</p>
          <p className="mt-1 text-xs text-ink2">
            {/* Says nothing about whether the account exists, or whether it had an address to
                send to. Either would turn this form into a way to ask who has an account. */}
            {t('If that account exists and has an email address, a link is on its way. It works once and expires in an hour.')}
          </p>
        </div>
        <Instructions title={t('No email address?')}>
          {t('Most customers do not have one. Ask the office to reset your password — they will give you a temporary one, and you choose your own when you sign in.')}
        </Instructions>
        <Button type="button" variant="primary" onClick={onDone}>
          {t('Back to sign in')}
        </Button>
      </div>
    );
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-4">
      <Instructions title={t('This only works if you have an email address')}>
        {t('If you signed up with a phone number only, ask the office to reset your password instead.')}
      </Instructions>

      <Field label={t('Email or phone number')} required>
        <Input
          required
          autoFocus
          autoComplete="username"
          value={identifier}
          onChange={(event) => setIdentifier(event.target.value)}
          placeholder={t('you@example.lk  or  077 123 4567')}
        />
      </Field>

      <ErrorBanner error={error} />

      <Button type="submit" variant="primary" disabled={busy || !identifier.trim()}>
        {busy ? t('Sending…') : t('Send me a link')}
      </Button>
      <Button type="button" variant="ghost" onClick={onDone}>
        {t('Back to sign in')}
      </Button>
    </form>
  );
}

function ChooseNewPassword({ token, onDone }: { token: string; onDone: () => void }) {
  const { t } = useTranslation();
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [done, setDone] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const fieldErrors = error instanceof ApiError ? error.fieldErrors : {};
  const mismatch = confirm !== '' && confirm !== password;

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.post('/api/v1/auth/reset-password', { token, password });
      // The token is spent. Taking it out of the address bar keeps it out of browser history and
      // out of whatever the next page sends as a Referer.
      window.history.replaceState({}, '', window.location.pathname);
      setDone(true);
    } catch (caught) {
      setError(caught);
    } finally {
      setBusy(false);
    }
  }

  if (done) {
    return (
      <div className="flex flex-col gap-4">
        <div className="rounded border-2 border-ok bg-oksoft px-3 py-2.5">
          <p className="text-sm font-semibold text-ok">{t('Password changed')}</p>
        </div>
        <Button type="button" variant="primary" onClick={onDone}>
          {t('Sign in')}
        </Button>
      </div>
    );
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-4">
      <Field
        label={t('New password')}
        required
        hint={t(PASSWORD_RULE)}
        error={passwordProblem(password) ?? fieldErrors.password}
      >
        <Input
          type="password"
          required
          autoFocus
          minLength={MIN_LENGTH}
          maxLength={MAX_LENGTH}
          autoComplete="new-password"
          aria-invalid={passwordProblem(password) !== null}
          value={password}
          onChange={(event) => setPassword(event.target.value)}
        />
      </Field>

      <Field
        label={t('Type it again')}
        required
        error={mismatch ? t('The two do not match.') : undefined}
      >
        <Input
          type="password"
          required
          maxLength={MAX_LENGTH}
          autoComplete="new-password"
          aria-invalid={mismatch}
          value={confirm}
          onChange={(event) => setConfirm(event.target.value)}
        />
      </Field>

      <ErrorBanner error={error} />

      <Button
        type="submit"
        variant="primary"
        disabled={busy || !isAcceptablePassword(password) || password !== confirm}
      >
        {busy ? t('Saving…') : t('Change my password')}
      </Button>
      <Button type="button" variant="ghost" onClick={onDone}>
        {t('Back to sign in')}
      </Button>
    </form>
  );
}
