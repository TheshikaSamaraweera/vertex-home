import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api, ApiError } from '../api/client';
import { Button, ErrorBanner, Field, Input } from '../components/ui';

/**
 * Account creation.
 *
 * An account is identified by an email address, a phone number, or both, and **at least one is
 * required**. Most of this client's customers have no email address at all, which is why the
 * number is a real identifier rather than a note on the record — and why there is no confirmation
 * link any more. A link is a door a large part of the intended membership could never walk
 * through.
 *
 * So this used to have five modes and now has two: fill the form, and you have an account. The
 * verification screens are gone along with the endpoints behind them.
 */
export function SignupPage({ onDone }: { onDone: () => void }) {
  const { t } = useTranslation();

  const [done, setDone] = useState(false);
  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [mobile, setMobile] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const fieldErrors = error instanceof ApiError ? error.fieldErrors : {};

  // The server enforces this too, and its answer is the one that counts. Checking here as well is
  // not belt-and-braces: it means the rule is visible while typing rather than after a round trip
  // that clears the password field.
  const hasIdentifier = email.trim() !== '' || mobile.trim() !== '';

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      // Blank is sent as undefined, not "". The two are different to the server: one means "no
      // email address", the other is a value that fails validation.
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
    <div className="flex min-h-dvh items-center justify-center bg-ground px-4 py-10">
      <div className="w-full max-w-sm">
        <div className="mb-6">
          <h1 className="text-2xl font-bold tracking-tight text-ink">MLM Sittu</h1>
          <p className="mt-1 text-sm text-ink2">{t('Create your account')}</p>
        </div>

        <div className="rounded-lg border border-rule bg-panel p-5">
          {!done && (
            <form onSubmit={submit} className="flex flex-col gap-4">
              <Field label={t('Full name')} error={fieldErrors.fullName}>
                <Input
                  required
                  autoFocus
                  value={fullName}
                  onChange={(event) => setFullName(event.target.value)}
                />
              </Field>

              <p className="text-xs text-ink3">
                {t('Give an email address or a phone number. Either one is enough, and you can give both.')}
              </p>

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

              <Button type="submit" variant="primary" disabled={busy || !hasIdentifier}>
                {busy ? t('Creating…') : t('Create account')}
              </Button>
              <Button type="button" variant="ghost" onClick={onDone}>
                {t('I already have an account')}
              </Button>
            </form>
          )}

          {done && (
            <div className="flex flex-col gap-4">
              <div className="rounded border border-ok bg-oksoft px-3 py-2.5">
                <p className="text-sm font-semibold text-ok">{t('Account created')}</p>
                <p className="mt-1 text-xs text-ink2">
                  {/* Deliberately does not confirm whether the details were new — saying so would
                      turn signup into a way to find out who already has an account. */}
                  {t('Sign in with the email address or phone number you entered, and the password you chose.')}
                </p>
              </div>
              <Button type="button" variant="primary" onClick={onDone}>
                {t('Sign in')}
              </Button>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
