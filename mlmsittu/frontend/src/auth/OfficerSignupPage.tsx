import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { api, ApiError } from '../api/client';
import {
  isAcceptablePassword,
  MAX_LENGTH,
  MIN_LENGTH,
  PASSWORD_RULE,
  passwordProblem,
} from '../lib/password';
import { Button, ErrorBanner, Field, Input, Instructions, PasswordInput } from '../components/ui';

/**
 * The marketing officer front door.
 *
 * A separate page rather than a role picker on the customer signup form. The two are different
 * applications with different outcomes — a customer registers a business and joins the referral
 * tree; an officer is credited for the customers they bring and joins nothing — and a dropdown
 * marked "what are you?" on a public form invites people to pick the wrong one.
 *
 * What this page is careful to say
 *
 * Creating the account and becoming an officer are not the same thing. The account works
 * immediately; the officer part waits for the office. Saying that here, before the form is
 * submitted rather than only after, is what stops somebody signing in the next day and concluding
 * the system has lost their application.
 */
export function OfficerSignupPage() {
  const { t } = useTranslation();

  const [done, setDone] = useState(false);
  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [mobile, setMobile] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const fieldErrors = error instanceof ApiError ? error.fieldErrors : {};
  const hasIdentifier = email.trim() !== '' || mobile.trim() !== '';

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      // Blank is sent as undefined, not "" — one means "no email address", the other is a value
      // that fails validation.
      await api.post('/api/v1/officer/signup', {
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
    <div className="flex min-h-dvh items-center justify-center bg-ground px-4 py-10">
      <div className="w-full max-w-sm">
        <div className="mb-6">
          <h1 className="text-2xl font-bold tracking-tight text-ink">Vertex Home Solutions</h1>
          <p className="mt-1 text-sm text-ink2">{t('Apply as a marketing officer')}</p>
        </div>

        <div className="rounded-lg border-2 border-rulestrong bg-panel p-5">
          {!done && (
            <form onSubmit={submit} className="flex flex-col gap-4">
              <Instructions title={t('The office approves marketing officers')}>
                {t(
                  'Your account is ready as soon as you fill this in, and you can sign in straight away. You are not a marketing officer until an administrator approves you — until then your portal will say so.',
                )}
              </Instructions>

              <Field label={t('Full name')} required error={fieldErrors.fullName}>
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
                  autoComplete="username"
                  value={email}
                  onChange={(event) => setEmail(event.target.value)}
                  placeholder={t('Leave blank if you have none')}
                />
              </Field>

              <Field label={t('Phone number')} error={fieldErrors.mobile}>
                <Input
                  type="tel"
                  autoComplete="tel"
                  value={mobile}
                  onChange={(event) => setMobile(event.target.value)}
                  placeholder="077 123 4567"
                />
              </Field>

              <Field
                label={t('Password')}
                required
                hint={t(PASSWORD_RULE)}
                error={passwordProblem(password) ?? fieldErrors.password}
              >
                <PasswordInput
                  required
                  minLength={MIN_LENGTH}
                  maxLength={MAX_LENGTH}
                  aria-invalid={passwordProblem(password) !== null}
                  autoComplete="new-password"
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                />
              </Field>

              <ErrorBanner error={error} />

              <Button
                type="submit"
                variant="primary"
                disabled={busy || !hasIdentifier || !isAcceptablePassword(password)}
              >
                {busy ? t('Applying…') : t('Apply')}
              </Button>

              <Link
                to="/"
                className="rounded px-3 py-2 text-center text-sm text-ink2 hover:bg-brandsoft hover:text-brand"
              >
                {t('I already have an account')}
              </Link>
            </form>
          )}

          {done && (
            <div className="flex flex-col gap-4">
              <div className="rounded border border-ok bg-oksoft px-3 py-2.5">
                <p className="text-sm font-semibold text-ok">{t('Application received')}</p>
                <p className="mt-1 text-xs text-ink2">
                  {/* Deliberately does not confirm whether the details were new. Saying so would
                      turn this form into a way to find out who already has an account. */}
                  {t(
                    'Sign in with the email address or phone number you entered. Your portal will tell you when the office has approved you.',
                  )}
                </p>
              </div>
              <Link
                to="/"
                className="rounded bg-brand px-3 py-2 text-center text-sm font-semibold text-brandink hover:bg-branddeep"
              >
                {t('Sign in')}
              </Link>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
