import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api, ApiError } from '../api/client';
import { Button, ErrorBanner, Field, Input } from '../components/ui';

/**
 * Account creation and email confirmation (P4-05, flow F-02 phase one).
 *
 * Both screens live here because they are two ends of the same journey — sign up, then click the
 * link. The verification view reads its token from the query string, which is what the emailed
 * link points at.
 *
 * Mobile verification is not built. Architecture §3.2 wants both channels confirmed before login;
 * until an SMS provider is chosen (§13.3) a confirmed email alone activates the account.
 */

type Mode = 'signup' | 'sent' | 'verifying' | 'verified' | 'failed';

export function SignupPage({ onDone }: { onDone: () => void }) {
  const { t } = useTranslation();

  const tokenFromLink = new URLSearchParams(window.location.search).get('token');
  const [mode, setMode] = useState<Mode>(tokenFromLink ? 'verifying' : 'signup');

  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [mobile, setMobile] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const fieldErrors = error instanceof ApiError ? error.fieldErrors : {};

  // The link lands on this page with ?token=…, so verification starts by itself.
  useEffect(() => {
    if (!tokenFromLink) return;
    void (async () => {
      try {
        await api.post('/api/v1/auth/verify-email', { token: tokenFromLink });
        setMode('verified');
        // Leave the token out of the address bar — it is spent, and it does not belong in
        // browser history or in whatever the next page sends as a Referer.
        window.history.replaceState({}, '', window.location.pathname);
      } catch (caught) {
        setError(caught);
        setMode('failed');
      }
    })();
  }, [tokenFromLink]);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.post('/api/v1/auth/register', { fullName, email, mobile, password });
      setMode('sent');
    } catch (caught) {
      setError(caught);
    } finally {
      setBusy(false);
    }
  }

  async function resend() {
    setBusy(true);
    setError(null);
    try {
      await api.post('/api/v1/auth/resend-verification', { email });
    } catch (caught) {
      setError(caught);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex min-h-dvh items-center justify-center bg-ground px-4 py-10">
      <div className="w-full max-w-sm">
        <div className="mb-6">
          <h1 className="text-2xl font-bold tracking-tight text-ink">MLM Sittu</h1>
          <p className="mt-1 text-sm text-ink2">{t('Create your account')}</p>
        </div>

        <div className="rounded-lg border border-rule bg-panel p-5">
          {mode === 'signup' && (
            <form onSubmit={submit} className="flex flex-col gap-4">
              <Field label={t('Full name')} error={fieldErrors.fullName}>
                <Input
                  required
                  autoFocus
                  value={fullName}
                  onChange={(event) => setFullName(event.target.value)}
                />
              </Field>
              <Field label={t('Email')} error={fieldErrors.email}>
                <Input
                  type="email"
                  required
                  autoComplete="username"
                  value={email}
                  onChange={(event) => setEmail(event.target.value)}
                />
              </Field>
              <Field label={t('Mobile')} hint={t('Optional for now — confirming it comes later')}>
                <Input
                  value={mobile}
                  onChange={(event) => setMobile(event.target.value)}
                  placeholder="+94 77 000 0000"
                />
              </Field>
              <Field
                label={t('Password')}
                hint={t('At least 10 characters. Longer beats complicated.')}
                error={fieldErrors.password}
              >
                <Input
                  type="password"
                  required
                  minLength={10}
                  autoComplete="new-password"
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                />
              </Field>

              <ErrorBanner error={error} />

              <Button type="submit" variant="primary" disabled={busy}>
                {busy ? t('Creating…') : t('Create account')}
              </Button>
              <Button type="button" variant="ghost" onClick={onDone}>
                {t('I already have an account')}
              </Button>
            </form>
          )}

          {mode === 'sent' && (
            <div className="flex flex-col gap-4">
              <h2 className="text-sm font-semibold text-ink">{t('Check your email')}</h2>
              <p className="text-sm text-ink2">
                {t('If that address can be registered, a confirmation link is on its way. The link works once and expires in 24 hours.')}
              </p>
              {/* Deliberately does not confirm whether the address was new — saying so would turn
                  signup into a way to find out who already has an account. */}
              <p className="text-xs text-ink3">
                {t('No email yet? Check spam, then request another.')}
              </p>
              <ErrorBanner error={error} />
              <Button type="button" onClick={() => void resend()} disabled={busy}>
                {t('Send it again')}
              </Button>
              <Button type="button" variant="ghost" onClick={onDone}>
                {t('Back to sign in')}
              </Button>
            </div>
          )}

          {mode === 'verifying' && (
            <p className="py-6 text-center text-sm text-ink2">{t('Confirming your email…')}</p>
          )}

          {mode === 'verified' && (
            <div className="flex flex-col gap-4">
              <div className="rounded border border-ok bg-oksoft px-3 py-2.5">
                <p className="text-sm font-semibold text-ok">{t('Email confirmed')}</p>
                <p className="mt-1 text-xs text-ink2">
                  {t('Your account is active. Sign in to submit your business registration.')}
                </p>
              </div>
              <Button type="button" variant="primary" onClick={onDone}>
                {t('Sign in')}
              </Button>
            </div>
          )}

          {mode === 'failed' && (
            <div className="flex flex-col gap-4">
              <ErrorBanner error={error} />
              <p className="text-xs text-ink2">
                {t('Links work once and last 24 hours. Enter your address and we will send a new one.')}
              </p>
              <Field label={t('Email')}>
                <Input
                  type="email"
                  value={email}
                  onChange={(event) => setEmail(event.target.value)}
                />
              </Field>
              <Button type="button" onClick={() => void resend()} disabled={busy || !email}>
                {t('Send a new link')}
              </Button>
              <Button type="button" variant="ghost" onClick={onDone}>
                {t('Back to sign in')}
              </Button>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
