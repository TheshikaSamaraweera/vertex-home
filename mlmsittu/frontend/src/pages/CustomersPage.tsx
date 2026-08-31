import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthContext';
import {
  useCreateCustomer,
  useCustomers,
  useSetCustomerActive,
  useUpdateCustomer,
  type Customer,
} from '../api/sales';
import { useDistributorRoots } from '../api/onboarding';
import {
  Badge,
  Button,
  Card,
  EmptyState,
  ErrorBanner,
  Field,
  Input,
  Modal,
  PageHeader,
  Select,
  Spinner,
  Table,
  TableWrap,
  Td,
  Th,
} from '../components/ui';

/**
 * Customers (P5-01).
 *
 * Anyone signed in can read the list — `support_agent` exists to answer questions about customers
 * — but only finance can change one. The buttons follow that, and so does the server.
 */
export function CustomersPage() {
  const { t } = useTranslation();
  const { hasRole } = useAuth();
  const canEdit = hasRole('FINANCE_OFFICER', 'SUPER_ADMIN');

  const [search, setSearch] = useState('');
  const [showInactive, setShowInactive] = useState(false);
  const customers = useCustomers(search || undefined, showInactive);

  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<Customer | null>(null);

  return (
    <>
      <PageHeader
        title={t('Buyers')}
        description={t('Who buys. Deactivating one stops new orders and leaves past orders untouched.')}
        actions={
          canEdit && (
            <Button variant="primary" size="sm" onClick={() => setCreating(true)}>
              {t('New buyer')}
            </Button>
          )
        }
      />

      <Card
        title={t('Buyer list')}
        actions={
          <>
            <Input
              className="w-56"
              placeholder={t('Search name or code…')}
              value={search}
              onChange={(event) => setSearch(event.target.value)}
            />
            <Button size="sm" onClick={() => setShowInactive(!showInactive)}>
              {showInactive ? t('Hide deactivated') : t('Show deactivated')}
            </Button>
          </>
        }
      >
        {customers.isLoading ? (
          <Spinner />
        ) : customers.error ? (
          <div className="p-4">
            <ErrorBanner error={customers.error} onRetry={() => void customers.refetch()} />
          </div>
        ) : (customers.data ?? []).length === 0 ? (
          <EmptyState
            message={t('No buyers match.')}
            hint={search ? t('Try a shorter search term.') : undefined}
          />
        ) : (
          <TableWrap>
            <Table>
              <thead>
                <tr>
                  <Th>{t('Code')}</Th>
                  <Th>{t('Name')}</Th>
                  <Th>{t('City')}</Th>
                  <Th>{t('Phone')}</Th>
                  <Th>{t('Status')}</Th>
                  <Th>{''}</Th>
                </tr>
              </thead>
              <tbody>
                {(customers.data ?? []).map((customer) => (
                  <tr key={customer.id} className={customer.active ? 'hover:bg-panel2' : 'opacity-60'}>
                    <Td className="font-mono text-xs">{customer.code}</Td>
                    <Td className="text-ink">{customer.name}</Td>
                    <Td className="text-xs">{customer.city ?? '—'}</Td>
                    <Td className="text-xs">{customer.phone ?? '—'}</Td>
                    <Td>
                      {customer.active ? (
                        <Badge tone="ok">{t('active')}</Badge>
                      ) : (
                        <Badge tone="danger">{t('deactivated')}</Badge>
                      )}
                    </Td>
                    <Td>
                      {canEdit && (
                        <div className="flex justify-end gap-1">
                          <Button size="sm" variant="ghost" onClick={() => setEditing(customer)}>
                            {t('Edit')}
                          </Button>
                          <ActiveToggle id={customer.id} active={!!customer.active} />
                        </div>
                      )}
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          </TableWrap>
        )}
      </Card>

      {creating && <CustomerModal onClose={() => setCreating(false)} />}
      {editing && <CustomerModal existing={editing} onClose={() => setEditing(null)} />}
    </>
  );
}

function ActiveToggle({ id, active }: { id?: string; active: boolean }) {
  const { t } = useTranslation();
  const setActive = useSetCustomerActive();
  return (
    <Button
      size="sm"
      variant="ghost"
      disabled={setActive.isPending}
      onClick={() => id && setActive.mutate({ id, active: !active })}
    >
      {active ? t('Deactivate') : t('Reactivate')}
    </Button>
  );
}

function CustomerModal({ existing, onClose }: { existing?: Customer; onClose: () => void }) {
  const { t } = useTranslation();
  const create = useCreateCustomer();
  const update = useUpdateCustomer();
  const distributors = useDistributorRoots();

  const [form, setForm] = useState({
    code: existing?.code ?? '',
    name: existing?.name ?? '',
    email: existing?.email ?? '',
    phone: existing?.phone ?? '',
    address: existing?.address ?? '',
    city: existing?.city ?? '',
    distributorId: existing?.distributorId ?? '',
    note: existing?.note ?? '',
  });

  const mutation = existing ? update : create;

  const body = {
    name: form.name,
    email: form.email || undefined,
    phone: form.phone || undefined,
    address: form.address || undefined,
    city: form.city || undefined,
    distributorId: form.distributorId || undefined,
    note: form.note || undefined,
  };

  return (
    <Modal title={existing ? t('Edit buyer') : t('New buyer')} onClose={onClose} wide>
      <form
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault();
          if (existing?.id) {
            update.mutate({ id: existing.id, body }, { onSuccess: onClose });
          } else {
            create.mutate({ code: form.code, ...body }, { onSuccess: onClose });
          }
        }}
      >
        <div className="grid gap-4 sm:grid-cols-2">
          <Field
            label={t('Code')}
            hint={existing ? t('The code cannot change — other records point at it') : undefined}
          >
            <Input
              required
              autoFocus={!existing}
              disabled={Boolean(existing)}
              value={form.code}
              onChange={(event) => setForm({ ...form, code: event.target.value })}
            />
          </Field>
          <Field label={t('Name')}>
            <Input
              required
              autoFocus={Boolean(existing)}
              value={form.name}
              onChange={(event) => setForm({ ...form, name: event.target.value })}
            />
          </Field>
        </div>

        <div className="grid gap-4 sm:grid-cols-2">
          <Field label={t('Email')}>
            <Input
              type="email"
              value={form.email ?? ''}
              onChange={(event) => setForm({ ...form, email: event.target.value })}
            />
          </Field>
          <Field label={t('Phone')}>
            <Input
              value={form.phone ?? ''}
              onChange={(event) => setForm({ ...form, phone: event.target.value })}
            />
          </Field>
        </div>

        <div className="grid gap-4 sm:grid-cols-2">
          <Field label={t('Address')}>
            <Input
              value={form.address ?? ''}
              onChange={(event) => setForm({ ...form, address: event.target.value })}
            />
          </Field>
          <Field label={t('City')}>
            <Input
              value={form.city ?? ''}
              onChange={(event) => setForm({ ...form, city: event.target.value })}
            />
          </Field>
        </div>

        <Field
          label={t('Introduced by')}
          hint={t('Attribution only. It confers nothing and is safe to leave blank.')}
        >
          <Select
            value={form.distributorId ?? ''}
            onChange={(event) => setForm({ ...form, distributorId: event.target.value })}
          >
            <option value="">{t('Nobody — walk-in')}</option>
            {(distributors.data ?? []).map((node) => (
              <option key={node.id} value={node.id ?? ''}>
                {node.businessId} — {node.fullName}
              </option>
            ))}
          </Select>
        </Field>

        <Field label={t('Note')}>
          <Input
            value={form.note ?? ''}
            onChange={(event) => setForm({ ...form, note: event.target.value })}
          />
        </Field>

        <ErrorBanner error={mutation.error} />

        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            {t('Cancel')}
          </Button>
          <Button type="submit" variant="primary" disabled={mutation.isPending}>
            {existing ? t('Save changes') : t('Create buyer')}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
