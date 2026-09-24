import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api, ApiError } from '../api/client';
import type { LoginResponse, UserSummary } from '../api/types';
import { Button, ErrorBanner, Field, Input, PasswordInput } from '../components/ui';
import { QrCode } from '../components/QrCode';
import { useAuth } from './AuthContext';

/**
 * The two-step sign-in from architecture 3.2, plus first-time enrolment.
 *
 * Three states, because the backend has three answers to a correct password:
 *
 * 1. `mfaRequired: false` — signed in, session cookie set.
 * 2. `mfaRequired: true` — send the six-digit code.
 * 3. `enrolmentRequired: true` — an administrator with no authenticator yet, shown a QR code to
 *    scan. The secret is
 *    returned exactly once, here, and is not stored server-side until a correct code proves the
 *    user actually saved it.
 */

type Stage =
  | { name: 'password' }
  | { name: 'code'; challengeId: string }
  | { name: 'enrol'; challengeId: string; secret: string; otpauthUri: string };

export function LoginPage({ onSignup }: { onSignup?: () => void }) {
  const { t } = useTranslation();
  const { setUser } = useAuth();

  const [stage, setStage] = useState<Stage>({ name: 'password' });
  const [identifier, setIdentifier] = useState('');
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  const fieldErrors = error instanceof ApiError ? error.fieldErrors : {};

  async function submitPassword(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const result = await api.post<LoginResponse>('/api/v1/auth/login', { identifier, password });

      if (!result.mfaRequired) {
        const me = await api.get<UserSummary>('/api/v1/auth/me');
        setUser(me);
        return;
      }
      if (result.enrolmentRequired && result.totpSecret && result.challengeId) {
        setStage({
          name: 'enrol',
          challengeId: result.challengeId,
          secret: result.totpSecret,
          otpauthUri: result.otpauthUri ?? '',
        });
        return;
      }
      setStage({ name: 'code', challengeId: result.challengeId ?? '' });
    } catch (caught) {
      setError(caught);
    } finally {
      setBusy(false);
    }
  }

  async function submitCode(event: React.FormEvent) {
    event.preventDefault();
    if (stage.name === 'password') return;
    setBusy(true);
    setError(null);
    try {
      await api.post<LoginResponse>('/api/v1/auth/login/totp', {
        challengeId: stage.challengeId,
        code,
      });
      const me = await api.get<UserSummary>('/api/v1/auth/me');
      setUser(me);
    } catch (caught) {
      setError(caught);
      setCode('');
      // A burned or expired challenge cannot be retried — the user has to start again, and saying
      // so is kinder than letting them type five more codes into a dead challenge.
      if (caught instanceof ApiError && caught.code === 'MFA_CHALLENGE_INVALID') {
        setStage({ name: 'password' });
        setPassword('');
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="grid min-h-dvh bg-ground lg:grid-cols-[minmax(0,1fr)_minmax(0,1.1fr)]">
      {/* The brand panel. Wide screens only: on a phone it would push the form below the fold,
          and the form is the only thing anybody opens this page to use. */}
      <aside className="relative hidden overflow-hidden bg-linear-to-br from-brand via-branddeep to-[#064b45] p-12 text-white lg:flex lg:flex-col lg:justify-between">
        <div aria-hidden className="pointer-events-none absolute -top-24 -left-24 h-80 w-80 rounded-full bg-white/10" />
        <div aria-hidden className="pointer-events-none absolute -right-32 -bottom-32 h-[28rem] w-[28rem] rounded-full bg-brandbright/25" />
        <div className="relative flex items-center gap-3">
          <span className="flex h-11 w-11 items-center justify-center rounded-xl bg-white text-[15px] font-extrabold text-brand shadow-sm">
            MS
          </span>
          <span className="text-lg font-bold tracking-tight">MLM Sittu</span>
        </div>
        <div className="relative max-w-md">
          <h2 className="text-[34px] leading-tight font-bold tracking-tight">
            {t('Stock, sales and your network, in one place.')}
          </h2>
          <ul className="mt-8 flex flex-col gap-4 text-[15px] text-white/90">
            {[
              t('Live stock across every store'),
              t('Orders from purchase to delivery'),
              t('Referral network and reward packs'),
            ].map((line) => (
              <li key={line} className="flex items-center gap-3">
                <span className="flex h-6 w-6 flex-none items-center justify-center rounded-full bg-white/15">
                  <svg aria-hidden viewBox="0 0 24 24" className="h-3.5 w-3.5" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M20 6 9 17l-5-5" />
                  </svg>
                </span>
                {line}
              </li>
            ))}
          </ul>
        </div>
        <p className="relative text-xs text-white/60">{t('Distribution and inventory platform')}</p>
      </aside>

      <div className="flex items-center justify-center px-4 py-10 sm:px-8">
        <div className="w-full max-w-[400px]">
          <div className="mb-8">
            {/* The mark again on narrow screens, where the brand panel is hidden. */}
            <span className="mb-6 flex h-11 w-11 items-center justify-center rounded-xl bg-linear-to-br from-brandbright to-brand text-[15px] font-extrabold text-white shadow-sm lg:hidden">
              MS
            </span>
            <h1 className="text-[28px] font-bold tracking-tight text-ink">{t('Welcome back')}</h1>
            <p className="mt-1.5 text-sm text-ink3">{t('Sign in to MLM Sittu to continue.')}</p>
          </div>

          <div className="rounded-2xl border border-rule bg-panel p-6 shadow-card sm:p-7">
            {stage.name === 'password' && (
              <form onSubmit={submitPassword} className="flex flex-col gap-4">
                <Field label={t('Email or phone number')} required error={fieldErrors.identifier}>
                  <Input
                    type="text"
                    autoComplete="username"
                    required
                    autoFocus
                    value={identifier}
                    onChange={(event) => setIdentifier(event.target.value)}
                    placeholder={t('you@example.lk  or  077 123 4567')}
                  />
                </Field>
                <Field label={t('Password')} required error={fieldErrors.password}>
                  <PasswordInput
                    autoComplete="current-password"
                    required
                    value={password}
                    onChange={(event) => setPassword(event.target.value)}
                  />
                </Field>
                <ErrorBanner error={error} />
                <Button type="submit" variant="primary" disabled={busy}>
                  {busy ? t('Signing in…') : t('Sign in')}
                </Button>
              </form>
            )}

            {stage.name === 'code' && (
              <form onSubmit={submitCode} className="flex flex-col gap-4">
                <div>
                  <h2 className="text-sm font-semibold text-ink">{t('Two-factor code')}</h2>
                  <p className="mt-1 text-xs text-ink2">
                    {t('Your password was accepted. Enter the six-digit code from your authenticator app.')}
                  </p>
                </div>
                <Field label={t('Code')}>
                  <Input
                    inputMode="numeric"
                    pattern="\d{6}"
                    maxLength={6}
                    required
                    autoFocus
                    autoComplete="one-time-code"
                    className="nums text-center text-lg tracking-[0.4em]"
                    value={code}
                    onChange={(event) => setCode(event.target.value.replace(/\D/g, ''))}
                  />
                </Field>
                <ErrorBanner error={error} />
                <Button type="submit" variant="primary" disabled={busy || code.length !== 6}>
                  {busy ? t('Checking…') : t('Verify')}
                </Button>
                <Button type="button" variant="ghost" onClick={() => setStage({ name: 'password' })}>
                  {t('Start again')}
                </Button>
              </form>
            )}

            {stage.name === 'enrol' && (
              <form onSubmit={submitCode} className="flex flex-col gap-4">
                <div>
                  <h2 className="text-sm font-semibold text-ink">{t('Set up two-factor sign-in')}</h2>
                  <p className="mt-1 text-xs text-ink2">
                    {t('Scan this with Google Authenticator, Microsoft Authenticator, Authy, or any TOTP app, then enter the six-digit code it shows.')}
                  </p>
                </div>

                {/* Scanning is the path almost everybody takes, so it leads. Typing a 32-character
                    key by hand is the fallback, not the instruction. */}
                {stage.otpauthUri && (
                  <div className="flex justify-center">
                    <QrCode value={stage.otpauthUri} />
                  </div>
                )}

                {/* On a phone the authenticator is on the same device, so there is nothing to scan
                    — the link hands the secret straight to the app. Hidden on wide screens, where
                    it would just be a link that opens nothing. */}
                {stage.otpauthUri && (
                  <a
                    href={stage.otpauthUri}
                    className="rounded-md border border-brand bg-brandsoft py-2 text-center text-xs font-medium text-brand sm:hidden"
                  >
                    {t('Open in my authenticator app')}
                  </a>
                )}

                <details className="rounded border border-rule bg-panel2 p-3">
                  <summary className="cursor-pointer text-xs font-medium text-ink2">
                    {t('Cannot scan? Enter the key by hand')}
                  </summary>
                  <p className="mt-2 text-[10px] font-semibold tracking-wider text-ink3 uppercase">
                    {t('Setup key')}
                  </p>
                  <p className="mt-1 font-mono text-sm break-all text-ink select-all">
                    {stage.secret}
                  </p>
                  <p className="mt-2 text-[11px] text-ink3">
                    {t('Time-based, 6 digits, 30 seconds — the defaults in every authenticator app.')}
                  </p>
                </details>

                <p className="text-xs text-ink3">
                  {t('Shown once. It is only stored after a correct code proves you have it.')}
                </p>

                <Field label={t('Code')}>
                  <Input
                    inputMode="numeric"
                    pattern="\d{6}"
                    maxLength={6}
                    required
                    autoFocus
                    className="nums text-center text-lg tracking-[0.4em]"
                    value={code}
                    onChange={(event) => setCode(event.target.value.replace(/\D/g, ''))}
                  />
                </Field>
                <ErrorBanner error={error} />
                <Button type="submit" variant="primary" disabled={busy || code.length !== 6}>
                  {busy ? t('Checking…') : t('Finish setup')}
                </Button>
              </form>
            )}
          </div>

          {onSignup && (
            <p className="mt-6 text-center text-sm text-ink3">
              {t('New customer?')}{' '}
              <button
                type="button"
                onClick={onSignup}
                className="font-semibold text-brand hover:text-branddeep hover:underline"
              >
                {t('Create an account')}
              </button>
            </p>
          )}

          {/* Development builds only. It used to be on the production sign-in page too, which
              told every visitor the seed password to try. */}
          {import.meta.env.DEV && (
            <p className="mt-3 text-center text-xs text-ink3">
              {t('Development seed accounts use the password')}{' '}
              <code className="font-mono text-ink2">Password123!</code>
            </p>
          )}
        </div>
      </div>
    </div>
  );
}
