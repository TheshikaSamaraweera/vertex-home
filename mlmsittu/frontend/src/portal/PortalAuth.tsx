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

  const [identifier, setIdentifier] = useState('');
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
        { identifier, password },
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
        {/* One field, either identifier. Most customers here have no email address, so asking
            for one by name would read as "you cannot sign in" to exactly the people this portal
            was built for. */}
        <Field label={t('Email or phone number')}>
          <Input
            type="text"
            required
            autoFocus
            autoComplete="username"
            value={identifier}
            onChange={(event) => setIdentifier(event.target.value)}
            placeholder={t('you@example.lk  or  077 123 4567')}
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

export function PortalSignupPage() {
  const { t } = useTranslation();

  const [done, setDone] = useState(false);
  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [mobile, setMobile] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const fieldErrors = error instanceof ApiError ? error.fieldErrors : {};

  // Mirrors the server's rule so it is visible while typing, rather than after a round trip that
  // clears the password field.
  const hasIdentifier = email.trim() !== '' || mobile.trim() !== '';

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      // Blank is sent as undefined rather than "": one means "no email address", the other is a
      // value that fails validation.
      await api.post('/api/v1/auth/register', {
        fullName,
        email: email.trim() || undefined,
        mobile: mobile.trim() || undefined,
        password,
      });
      setDone(true);
    } catch (caught) {
      setError(caught);
    } finally {
      setBusy(false);
    }
  }

  return (
    <PortalFrame title={t('Create your account')} subtitle={t('Become a customer')}>
      {!done && (
        <form onSubmit={(event) => void submit(event)} className="flex flex-col gap-4">
          <p className="text-xs text-ink2">
            {t('This is step one of two: create an account, then register your business. Nothing opens until the registration is approved.')}
          </p>
          <Field label={t('Full name')} error={fieldErrors.fullName}>
            <Input required autoFocus value={fullName} onChange={(e) => setFullName(e.target.value)} />
          </Field>

          <p className="text-xs text-ink3">
            {t('Give an email address or a phone number. Either one is enough, and you can give both.')}
          </p>

          <Field label={t('Email')} error={fieldErrors.email}>
            <Input
              type="email"
              autoComplete="username"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder={t('Leave blank if you have none')}
            />
          </Field>
          <Field label={t('Phone number')} error={fieldErrors.mobile}>
            <Input
              type="tel"
              autoComplete="tel"
              value={mobile}
              onChange={(e) => setMobile(e.target.value)}
              placeholder="077 123 4567"
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

          <Button type="submit" variant="primary" disabled={busy || !hasIdentifier}>
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

      {done && (
        <div className="flex flex-col gap-4">
          <div className="rounded-md border border-ok bg-oksoft px-3 py-2.5">
            <p className="text-sm font-semibold text-ok">{t('Account created')}</p>
            <p className="mt-1 text-xs text-ink2">
              {/* Says nothing about whether the details were new: that would turn signup into a
                  way to find out who already has an account. */}
              {t('Sign in with what you entered, then register your business — that is the last step.')}
            </p>
          </div>
          <Link to="/portal/login">
            <Button variant="primary" className="w-full">
              {t('Sign in')}
            </Button>
          </Link>
        </div>
      )}
    </PortalFrame>
  );
}
