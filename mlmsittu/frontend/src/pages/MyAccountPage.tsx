import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api, ApiError } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import {
  isAcceptablePassword,
  MAX_LENGTH,
  MIN_LENGTH,
  PASSWORD_RULE,
  passwordProblem,
} from '../lib/password';
import {
  Button,
  Card,
  ErrorBanner,
  Field,
  Input,
  Instructions,
  PageHeader,
} from '../components/ui';

/** Where a profile photograph is fetched from. Signed in only — these are pictures of people. */
export const profilePhotoUrl = (id: string) => `/api/v1/users/photo/${id}`;

/**
 * Your own account: your picture and your password.
 *
 * <p>One page for staff and customers alike. Nothing here is about roles — it is the two things
 * anybody signed in might want to change about themselves.
 */
export function MyAccountPage() {
  const { t } = useTranslation();
  const { user } = useAuth();

  return (
    <>
      <PageHeader
        title={t('My account')}
        description={t('Your photograph and your password.')}
      />

      {user?.mustChangePassword && (
        <div className="mb-5">
          <Instructions title={t('Choose your own password')}>
            {t('You are signed in with a temporary password somebody at the office set for you. Choose your own below — until you do, this is the only page that opens.')}
          </Instructions>
        </div>
      )}

      <div className="grid gap-5 lg:grid-cols-2">
        <ProfilePhotoCard />
        <ChangePasswordCard />
      </div>
    </>
  );
}

function ProfilePhotoCard() {
  const { t } = useTranslation();
  const { user, refresh } = useAuth();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  async function upload(file: File | undefined) {
    if (!file) return;
    setBusy(true);
    setError(null);
    try {
      const form = new FormData();
      form.append('file', file);
      // Raw fetch: the browser has to set its own multipart boundary, and the api helper's JSON
      // content type would make the request unparseable server-side.
      const response = await fetch('/api/v1/users/me/photo', {
        method: 'POST',
        credentials: 'include',
        body: form,
      });
      if (!response.ok) {
        const payload = await response.json().catch(() => undefined);
        throw Object.assign(new Error(payload?.detail ?? 'Upload failed'), {
          code: payload?.code ?? 'UPLOAD_FAILED',
        });
      }
      await refresh();
    } catch (caught) {
      setError(caught);
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    setBusy(true);
    setError(null);
    try {
      await api.del('/api/v1/users/me/photo');
      await refresh();
    } catch (caught) {
      setError(caught);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card title={t('Photograph')}>
      <div className="flex flex-wrap items-center gap-5 p-5">
        <Avatar
          photoId={user?.profilePhotoId ?? null}
          name={user?.fullName ?? ''}
          size={88}
        />
        <div className="min-w-0 flex-1">
          <input
            type="file"
            accept="image/jpeg,image/png"
            disabled={busy}
            onChange={(event) => void upload(event.target.files?.[0])}
            className="w-full rounded border-2 border-rulestrong bg-panel px-3 py-2 text-sm text-ink2 file:mr-3 file:rounded file:border-0 file:bg-brandsoft file:px-3 file:py-1 file:text-brand"
          />
          <p className="mt-2 text-xs text-ink3">
            {t('JPEG or PNG. Location data is stripped when it is saved.')}
          </p>
          {user?.profilePhotoId && (
            <Button size="sm" variant="ghost" className="mt-2" onClick={() => void remove()}>
              {t('Remove photograph')}
            </Button>
          )}
          <ErrorBanner error={error} />
        </div>
      </div>
    </Card>
  );
}

function ChangePasswordCard() {
  const { t } = useTranslation();
  const { user, refresh } = useAuth();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [done, setDone] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const fieldErrors = error instanceof ApiError ? error.fieldErrors : {};
  const mismatch = confirm !== '' && confirm !== next;

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.post('/api/v1/auth/change-password', {
        currentPassword: current,
        newPassword: next,
      });
      setCurrent('');
      setNext('');
      setConfirm('');
      setDone(true);
      // The forced-change flag is cleared server-side; refreshing is what lets the rest of the
      // application open again.
      await refresh();
    } catch (caught) {
      setError(caught);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card title={t('Password')}>
      <form onSubmit={submit} className="flex flex-col gap-4 p-5">
        {done && (
          <div className="rounded border-2 border-ok bg-oksoft px-3 py-2.5">
            <p className="text-sm font-semibold text-ok">{t('Password changed')}</p>
          </div>
        )}

        <Field
          label={user?.mustChangePassword ? t('Temporary password') : t('Current password')}
          required
          hint={
            user?.mustChangePassword
              ? t('The one the office gave you')
              : undefined
          }
          error={fieldErrors.currentPassword}
        >
          <Input
            type="password"
            required
            autoComplete="current-password"
            value={current}
            onChange={(event) => setCurrent(event.target.value)}
          />
        </Field>

        <Field
          label={t('New password')}
          required
          hint={t(PASSWORD_RULE)}
          error={passwordProblem(next) ?? fieldErrors.newPassword}
        >
          <Input
            type="password"
            required
            minLength={MIN_LENGTH}
            maxLength={MAX_LENGTH}
            autoComplete="new-password"
            aria-invalid={passwordProblem(next) !== null}
            value={next}
            onChange={(event) => setNext(event.target.value)}
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
          disabled={busy || !current || !isAcceptablePassword(next) || next !== confirm}
        >
          {busy ? t('Saving…') : t('Change my password')}
        </Button>
      </form>
    </Card>
  );
}

/**
 * A photograph, or the person's initials.
 *
 * <p>Initials rather than a generic silhouette: in a list of twenty customers a row of identical
 * grey figures tells you nothing, where two letters at least distinguishes them.
 */
export function Avatar({
  photoId,
  name,
  size = 32,
}: {
  photoId?: string | null;
  name: string;
  size?: number;
}) {
  const initials = name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? '')
    .join('');

  if (photoId) {
    return (
      <img
        src={profilePhotoUrl(photoId)}
        alt=""
        width={size}
        height={size}
        style={{ width: size, height: size }}
        className="flex-none rounded-full border-2 border-rulestrong object-cover"
      />
    );
  }

  return (
    <span
      aria-hidden
      style={{ width: size, height: size, fontSize: Math.round(size * 0.36) }}
      className="flex flex-none items-center justify-center rounded-full border-2 border-rulestrong bg-brandsoft font-bold text-brand"
    >
      {initials || '?'}
    </span>
  );
}
