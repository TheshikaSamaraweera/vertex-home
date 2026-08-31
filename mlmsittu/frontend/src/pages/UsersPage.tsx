import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useReplaceUserRoles, useUsers } from '../api/queries';
import { ROLES, type Role, type UserSummary } from '../api/types';
import { ROLE_LABELS, useAuth } from '../auth/AuthContext';
import {
  Badge,
  Button,
  Card,
  ErrorBanner,
  Modal,
  PageHeader,
  Spinner,
  Table,
  TableWrap,
  Td,
  Th,
} from '../components/ui';

/**
 * Roles are replaced wholesale, never added one at a time.
 *
 * An incremental "add role" API makes the resulting privileges depend on the order requests
 * arrived in, which is a poor property for the screen that decides who may approve payments.
 */
export function UsersPage() {
  const { t } = useTranslation();
  const { user: me } = useAuth();
  const users = useUsers();
  const [editing, setEditing] = useState<UserSummary | null>(null);

  return (
    <>
      <PageHeader
        title={t('Users and roles')}
        description={t('Every change here is written to the audit log with the before and after role sets.')}
      />

      <Card title={t('Accounts')}>
        {users.isLoading ? (
          <Spinner />
        ) : users.error ? (
          <div className="p-4">
            <ErrorBanner error={users.error} onRetry={() => void users.refetch()} />
          </div>
        ) : (
          <TableWrap>
            <Table>
              <thead>
                <tr>
                  <Th>{t('Name')}</Th>
                  <Th>{t('Email')}</Th>
                  <Th>{t('Roles')}</Th>
                  <Th>{t('2FA')}</Th>
                  <Th>{t('Status')}</Th>
                  <Th>{''}</Th>
                </tr>
              </thead>
              <tbody>
                {(users.data ?? []).map((user) => (
                  <tr key={user.id} className="hover:bg-panel2">
                    <Td>
                      <span className="text-ink">{user.fullName}</span>
                      {user.id === me?.id && (
                        <span className="ml-2 text-[11px] text-ink3">{t('(you)')}</span>
                      )}
                    </Td>
                    <Td className="text-xs">{user.email}</Td>
                    <Td>
                      <div className="flex flex-wrap gap-1">
                        {(user.roles ?? []).map((role) => (
                          <Badge key={role} tone="brand">
                            {ROLE_LABELS[role as Role] ?? role}
                          </Badge>
                        ))}
                      </div>
                    </Td>
                    <Td>
                      {user.totpEnabled ? (
                        <Badge tone="ok">{t('enrolled')}</Badge>
                      ) : (
                        <Badge tone="warn">{t('not set up')}</Badge>
                      )}
                    </Td>
                    <Td>
                      <Badge tone={user.status === 'active' ? 'ok' : 'danger'}>{user.status}</Badge>
                    </Td>
                    <Td>
                      <div className="flex justify-end">
                        <Button size="sm" variant="ghost" onClick={() => setEditing(user)}>
                          {t('Change roles')}
                        </Button>
                      </div>
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          </TableWrap>
        )}
      </Card>

      {editing && <RolesModal user={editing} onClose={() => setEditing(null)} />}
    </>
  );
}

function RolesModal({ user, onClose }: { user: UserSummary; onClose: () => void }) {
  const { t } = useTranslation();
  const { user: me, refresh } = useAuth();
  const replace = useReplaceUserRoles();

  const [selected, setSelected] = useState<string[]>(user.roles ?? []);

  const isSelf = user.id === me?.id;
  const removingOwnSuperAdmin =
    isSelf && (user.roles ?? []).includes('SUPER_ADMIN') && !selected.includes('SUPER_ADMIN');

  return (
    <Modal title={`${t('Roles')} — ${user.fullName}`} onClose={onClose}>
      <form
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault();
          if (!user.id) return;
          replace.mutate(
            { id: user.id, roleCodes: selected },
            {
              onSuccess: async () => {
                // Changing your own roles changes your own navigation.
                if (isSelf) await refresh();
                onClose();
              },
            },
          );
        }}
      >
        <p className="text-xs text-ink2">
          {t('The full set is replaced. Anything not ticked is removed.')}
        </p>

        <div className="flex flex-col gap-1.5">
          {ROLES.map((role) => (
            <label
              key={role}
              className="flex items-center gap-2.5 rounded border border-rule px-3 py-2 text-sm hover:border-brand"
            >
              <input
                type="checkbox"
                checked={selected.includes(role)}
                onChange={(event) =>
                  setSelected(
                    event.target.checked
                      ? [...selected, role]
                      : selected.filter((candidate) => candidate !== role),
                  )
                }
              />
              <span className="text-ink">{ROLE_LABELS[role]}</span>
              <span className="ml-auto font-mono text-[10px] text-ink3">{role}</span>
            </label>
          ))}
        </div>

        {/* The backend refuses this outright. Saying so before the click saves a confusing 403. */}
        {removingOwnSuperAdmin && (
          <p className="rounded border border-danger bg-dangersoft px-3 py-2 text-xs text-danger">
            {t('You cannot remove your own super admin role — you would not be able to undo it.')}
          </p>
        )}

        <ErrorBanner error={replace.error} />

        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            {t('Cancel')}
          </Button>
          <Button
            type="submit"
            variant="primary"
            disabled={replace.isPending || selected.length === 0}
          >
            {replace.isPending ? t('Saving…') : t('Replace roles')}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
