import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { api, ApiError } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { Button, ErrorBanner, Field, Input } from '../components/ui';

/**
 * The distributor's front door — a separate page from the staff one.
 *
 * The two are separate because the audiences are: a distributor arrives from a referral link,
 * creates their own account, and never touches stock or payments. Staff accounts are created by an
 * administrator and require an authenticator app. One page trying to serve both ends up explaining
 * itself to everybody.
 *
 * The backend endpoint is shared — there is one session mechanism, and one place that checks a
 * password. What differs is who is expected here and where they land afterwards.
 */

function PortalFrame({
  title,
  subtitle,
  children,
}: {
  title: string;
  subtitle: string;
  children: React.ReactNode;
}) {
  const { t } = useTranslation();
  return (
    <div className="flex min-h-dvh items-center justify-center bg-ground px-4 py-10">
      <div className="w-full max-w-sm">
        <div className="mb-6 flex items-center gap-2.5">
          <span
            aria-hidden
            className="flex h-9 w-9 flex-none items-center justify-center rounded-lg bg-brand text-sm font-bold text-brandink"
          >
            MS
          </span>
          <div>
            <h1 className="text-lg font-bold tracking-tight text-ink">{title}</h1>
            <p className="text-xs text-ink2">{subtitle}</p>
          </div>
        </div>

        <div className="rounded-xl border border-rule bg-panel p-5 shadow-card">{children}</div>

        <p className="mt-5 text-center text-xs text-ink3">
          {t('Staff member?')}{' '}
          <Link className="text-brand underline" to="/">
            {t('Sign in here instead')}
          </Link>
        </p>
      </div>
    </div>
  );
}

// ================================================================== sign in

export function PortalLoginPage() {
  const { t } = useTranslation();
  const { setUser } = useAuth();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const result = await api.post<{ mfaRequired?: boolean; user?: unknown }>(
        '/api/v1/auth/login',
        { email, password },
      );

      // Distributors carry a role that does not require an authenticator, so a successful password
      // is the whole of it. If a staff account signs in here they get the MFA challenge instead,
      // and are told where to go.
      if (result.mfaRequired) {
        setError(
          new ApiError(
            400,
            'STAFF_ACCOUNT',
            'That is a staff account. Use the staff sign-in page.',
            {},
          ),
        );
        return;
      }
      setUser(result.user as never);
    } catch (caught) {
      setError(caught);
    } finally {
      setBusy(false);
    }
  }

  return (
    <PortalFrame title={t('Customer sign in')} subtitle={t('MLM Sittu')}>
      <form onSubmit={(event) => void submit(event)} className="flex flex-col gap-4">
        <Field label={t('Email')}>
          <Input
            type="email"
            required
            autoFocus
            autoComplete="username"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
          />
        </Field>
        <Field label={t('Password')}>
          <Input
            type="password"
            required
            autoComplete="current-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />
        </Field>

        <ErrorBanner error={error} />

        <Button type="submit" variant="primary" disabled={busy}>
          {busy ? t('Signing in…') : t('Sign in')}
        </Button>

        <p className="text-center text-xs text-ink2">
          {t('New customer?')}{' '}
          <Link className="text-brand underline" to="/portal/signup">
            {t('Create an account')}
          </Link>
        </p>
      </form>
    </PortalFrame>
  );
}

// ================================================================== create an account

type Mode = 'signup' | 'sent' | 'verifying' | 'verified' | 'failed';

export function PortalSignupPage() {
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

  // The confirmation link lands here with ?token=…, so verification starts by itself.
  if (tokenFromLink && mode === 'verifying') {
    void api
      .post('/api/v1/auth/verify-email', { token: tokenFromLink })
      .then(() => {
        setMode('verified');
        window.history.replaceState({}, '', window.location.pathname);
      })
      .catch((caught) => {
        setError(caught);
        setMode('failed');
      });
  }

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

  return (
    <PortalFrame title={t('Create your account')} subtitle={t('Become a customer')}>
      {mode === 'signup' && (
        <form onSubmit={(event) => void submit(event)} className="flex flex-col gap-4">
          <p className="text-xs text-ink2">
            {t('This is step one of three: create an account, confirm your email, then register your business. Nothing opens until the registration is approved.')}
          </p>

          <Field label={t('Full name')} error={fieldErrors.fullName}>
            <Input required autoFocus value={fullName} onChange={(e) => setFullName(e.target.value)} />
          </Field>
          <Field label={t('Email')} error={fieldErrors.email}>
            <Input
              type="email"
              required
              autoComplete="username"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
            />
          </Field>
          <Field label={t('Mobile')} hint={t('Optional for now')}>
            <Input
              value={mobile}
              onChange={(e) => setMobile(e.target.value)}
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
              onChange={(e) => setPassword(e.target.value)}
            />
          </Field>

          <ErrorBanner error={error} />

          <Button type="submit" variant="primary" disabled={busy}>
            {busy ? t('Creating…') : t('Create account')}
          </Button>
          <p className="text-center text-xs text-ink2">
            {t('Already have one?')}{' '}
            <Link className="text-brand underline" to="/portal/login">
              {t('Sign in')}
            </Link>
          </p>
        </form>
      )}

      {mode === 'sent' && (
        <div className="flex flex-col gap-4">
          <h2 className="text-sm font-semibold text-ink">{t('Check your email')}</h2>
          <p className="text-sm text-ink2">
            {t('If that address can be registered, a confirmation link is on its way. It works once and expires in 24 hours.')}
          </p>
          <Link to="/portal/login">
            <Button className="w-full">{t('Back to sign in')}</Button>
          </Link>
        </div>
      )}

      {mode === 'verifying' && (
        <p className="py-6 text-center text-sm text-ink2">{t('Confirming your email…')}</p>
      )}

      {mode === 'verified' && (
        <div className="flex flex-col gap-4">
          <div className="rounded-md border border-ok bg-oksoft px-3 py-2.5">
            <p className="text-sm font-semibold text-ok">{t('Email confirmed')}</p>
            <p className="mt-1 text-xs text-ink2">
              {t('Sign in and register your business — that is the last step.')}
            </p>
          </div>
          <Link to="/portal/login">
            <Button variant="primary" className="w-full">
              {t('Sign in')}
            </Button>
          </Link>
        </div>
      )}

      {mode === 'failed' && (
        <div className="flex flex-col gap-4">
          <ErrorBanner error={error} />
          <p className="text-xs text-ink2">
            {t('Links work once and last 24 hours. Sign in and request another if this one has expired.')}
          </p>
          <Link to="/portal/login">
            <Button className="w-full">{t('Back to sign in')}</Button>
          </Link>
        </div>
      )}
    </PortalFrame>
  );
}
