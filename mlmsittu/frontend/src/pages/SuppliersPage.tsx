import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  useCreateSupplier,
  useDeleteSupplier,
  useSetSupplierActive,
  useSuppliers,
  useUpdateSupplier,
} from '../api/queries';
import type { Supplier } from '../api/types';
import { useAuth } from '../auth/AuthContext';
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
  Spinner,
  Table,
  TableWrap,
  Td,
  Th,
} from '../components/ui';

/**
 * Suppliers, as a record of the company rather than a name to hang orders on.
 *
 * Split from purchase orders at the client's request, and the split is real rather than cosmetic:
 * this screen answers "who do we buy from and how do we reach them", which is a different question
 * from "what have we ordered". They also change on different timescales — a supplier's details are
 * edited once a year, an order every day.
 *
 * **Two sets of contact details, deliberately.** The company has a switchboard and an accounts
 * address; the named contact has their own number and their own address. Collapsing them into one
 * pair is how you end up ringing reception to chase an order.
 */
export function SuppliersPage() {
  const { t } = useTranslation();
  const { hasRole } = useAuth();
  const canWrite = hasRole('PROCUREMENT_OFFICER');

  const [search, setSearch] = useState('');
  const [showInactive, setShowInactive] = useState(true);
  const suppliers = useSuppliers(showInactive);
  const remove = useDeleteSupplier();

  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<Supplier | null>(null);
  const [confirmingDelete, setConfirmingDelete] = useState<Supplier | null>(null);

  const filtered = (suppliers.data ?? []).filter((supplier) => {
    if (!search) return true;
    const needle = search.toLowerCase();
    return [supplier.name, supplier.code, supplier.contactName, supplier.email, supplier.address]
      .filter(Boolean)
      .some((field) => (field ?? '').toLowerCase().includes(needle));
  });

  return (
    <>
      <PageHeader
        title={t('Suppliers')}
        description={t('Who you buy from, and how to reach them.')}
        actions={
          canWrite && (
            <Button variant="primary" size="sm" onClick={() => setCreating(true)}>
              {t('New supplier')}
            </Button>
          )
        }
      />

      {remove.error != null && (
        <div className="mb-4">
          <ErrorBanner error={remove.error} />
        </div>
      )}

      <Card
        title={t('Supplier list')}
        subtitle={t('{{count}} shown', { count: filtered.length })}
        actions={
          <>
            <Input
              className="w-64"
              type="search"
              aria-label={t('Search suppliers')}
              placeholder={t('Search company, contact or location…')}
              value={search}
              onChange={(event) => setSearch(event.target.value)}
            />
            <Button size="sm" onClick={() => setShowInactive(!showInactive)}>
              {showInactive ? t('Active only') : t('Include deactivated')}
            </Button>
          </>
        }
      >
        {suppliers.isLoading ? (
          <Spinner />
        ) : suppliers.error ? (
          <div className="p-4">
            <ErrorBanner error={suppliers.error} onRetry={() => void suppliers.refetch()} />
          </div>
        ) : filtered.length === 0 ? (
          <EmptyState message={t('No suppliers match.')} />
        ) : (
          <TableWrap>
            <Table>
              <thead>
                <tr>
                  <Th>{t('Code')}</Th>
                  <Th>{t('Company')}</Th>
                  <Th>{t('Location')}</Th>
                  <Th>{t('Company contact')}</Th>
                  <Th>{t('Contact person')}</Th>
                  <Th>{t('Status')}</Th>
                  <Th>{''}</Th>
                </tr>
              </thead>
              <tbody>
                {filtered.map((supplier) => (
                  <tr
                    key={supplier.id}
                    className={supplier.active ? 'hover:bg-panel2' : 'opacity-60'}
                  >
                    <Td className="font-mono text-xs">{supplier.code}</Td>
                    <Td className="text-ink">{supplier.name}</Td>
                    <Td className="text-xs">{supplier.address ?? '—'}</Td>
                    <Td className="text-xs">
                      <p>{supplier.phone ?? '—'}</p>
                      <p className="text-ink3">{supplier.email ?? '—'}</p>
                    </Td>
                    <Td className="text-xs">
                      {/* The person, then their own number and address — the ones you actually
                          use when an order is late. */}
                      <p className="text-ink">{supplier.contactName ?? '—'}</p>
                      <p className="text-ink3">
                        {supplier.contactPhone ?? '—'}
                        {supplier.contactEmail ? ` · ${supplier.contactEmail}` : ''}
                      </p>
                    </Td>
                    <Td>
                      {supplier.active ? (
                        <Badge tone="ok">{t('active')}</Badge>
                      ) : (
                        <Badge tone="danger">{t('deactivated')}</Badge>
                      )}
                    </Td>
                    <Td>
                      {canWrite && (
                        <div className="flex justify-end gap-1">
                          <Button size="sm" onClick={() => setEditing(supplier)}>
                            {t('Edit')}
                          </Button>
                          <ActiveToggle id={supplier.id} active={!!supplier.active} />
                          <Button
                            size="sm"
                            variant="danger"
                            onClick={() => setConfirmingDelete(supplier)}
                          >
                            {t('Delete')}
                          </Button>
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

      {creating && <SupplierModal onClose={() => setCreating(false)} />}
      {editing && <SupplierModal existing={editing} onClose={() => setEditing(null)} />}
      {confirmingDelete && (
        <ConfirmDeleteSupplier
          supplier={confirmingDelete}
          pending={remove.isPending}
          onCancel={() => setConfirmingDelete(null)}
          onConfirm={() => {
            if (!confirmingDelete.id) return;
            remove.mutate(confirmingDelete.id, {
              onSuccess: () => setConfirmingDelete(null),
              // Refused for a supplier with history. Close anyway, so the banner explaining why
              // is not hidden behind the dialog.
              onError: () => setConfirmingDelete(null),
            });
          }}
        />
      )}
    </>
  );
}

function ActiveToggle({ id, active }: { id?: string; active: boolean }) {
  const { t } = useTranslation();
  const setActive = useSetSupplierActive();
  return (
    <Button
      size="sm"
      variant="ghost"
      disabled={setActive.isPending}
      onClick={() => id && setActive.mutate({ id, active: !active })}
      title={t('Deactivating stops new orders and leaves existing ones alone')}
    >
      {active ? t('Deactivate') : t('Reactivate')}
    </Button>
  );
}

/** One form for create and edit. The code is fixed once orders point at it. */
function SupplierModal({ existing, onClose }: { existing?: Supplier; onClose: () => void }) {
  const { t } = useTranslation();
  const create = useCreateSupplier();
  const update = useUpdateSupplier();
  const mutation = existing ? update : create;

  const [form, setForm] = useState({
    code: existing?.code ?? '',
    name: existing?.name ?? '',
    address: existing?.address ?? '',
    phone: existing?.phone ?? '',
    email: existing?.email ?? '',
    contactName: existing?.contactName ?? '',
    contactPhone: existing?.contactPhone ?? '',
    contactEmail: existing?.contactEmail ?? '',
  });

  const set = (key: keyof typeof form) => (value: string) =>
    setForm((previous) => ({ ...previous, [key]: value }));

  const body = {
    name: form.name,
    address: form.address || undefined,
    phone: form.phone || undefined,
    email: form.email || undefined,
    contactName: form.contactName || undefined,
    contactPhone: form.contactPhone || undefined,
    contactEmail: form.contactEmail || undefined,
  };

  return (
    <Modal title={existing ? t('Edit supplier') : t('New supplier')} onClose={onClose} wide>
      <form
        className="flex flex-col gap-5"
        onSubmit={(event) => {
          event.preventDefault();
          if (existing?.id) {
            update.mutate({ id: existing.id, body }, { onSuccess: onClose });
          } else {
            create.mutate({ code: form.code, ...body }, { onSuccess: onClose });
          }
        }}
      >
        <div>
          <p className="mb-2 text-xs font-semibold tracking-wide text-ink2 uppercase">
            {t('The company')}
          </p>
          <div className="grid gap-4 sm:grid-cols-2">
            <Field
              label={t('Code')}
              hint={existing ? t('The code cannot change — orders point at it') : undefined}
            >
              <Input
                required
                autoFocus={!existing}
                disabled={Boolean(existing)}
                value={form.code}
                onChange={(event) => set('code')(event.target.value)}
                placeholder="SUP-003"
              />
            </Field>
            <Field label={t('Company name')}>
              <Input
                required
                autoFocus={Boolean(existing)}
                value={form.name}
                onChange={(event) => set('name')(event.target.value)}
              />
            </Field>
          </div>
          <div className="mt-4 grid gap-4 sm:grid-cols-3">
            <Field label={t('Location')}>
              <Input
                value={form.address}
                onChange={(event) => set('address')(event.target.value)}
                placeholder={t('Colombo, Sri Lanka')}
              />
            </Field>
            <Field label={t('Contact number')}>
              <Input value={form.phone} onChange={(event) => set('phone')(event.target.value)} />
            </Field>
            <Field label={t('Email')}>
              <Input
                type="email"
                value={form.email}
                onChange={(event) => set('email')(event.target.value)}
              />
            </Field>
          </div>
        </div>

        <div>
          <p className="mb-1 text-xs font-semibold tracking-wide text-ink2 uppercase">
            {t('Contact person')}
          </p>
          <p className="mb-2 text-xs text-ink3">
            {t('The person you speak to, and their own number and address — not the switchboard.')}
          </p>
          <div className="grid gap-4 sm:grid-cols-3">
            <Field label={t('Name')}>
              <Input
                value={form.contactName}
                onChange={(event) => set('contactName')(event.target.value)}
              />
            </Field>
            <Field label={t('Phone number')}>
              <Input
                value={form.contactPhone}
                onChange={(event) => set('contactPhone')(event.target.value)}
              />
            </Field>
            <Field label={t('Email')}>
              <Input
                type="email"
                value={form.contactEmail}
                onChange={(event) => set('contactEmail')(event.target.value)}
              />
            </Field>
          </div>
        </div>

        <ErrorBanner error={mutation.error} />

        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            {t('Cancel')}
          </Button>
          <Button type="submit" variant="primary" disabled={mutation.isPending}>
            {mutation.isPending
              ? t('Saving…')
              : existing
                ? t('Save changes')
                : t('Create supplier')}
          </Button>
        </div>
      </form>
    </Modal>
  );
}

function ConfirmDeleteSupplier({
  supplier,
  pending,
  onCancel,
  onConfirm,
}: {
  supplier: Supplier;
  pending: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const { t } = useTranslation();
  return (
    <Modal title={t('Delete this supplier?')} onClose={onCancel}>
      <div className="flex flex-col gap-4">
        <p className="text-sm text-ink2">
          {t('“{{name}}” ({{code}}) will be removed completely.', {
            name: supplier.name,
            code: supplier.code,
          })}
        </p>
        <p className="text-xs text-ink3">
          {t('If you have ever ordered from them, or recorded a price for them, the deletion is refused — those records point here and would be orphaned. Deactivate instead: it stops new orders and keeps the history.')}
        </p>
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onCancel}>
            {t('Cancel')}
          </Button>
          <Button type="button" variant="danger" disabled={pending} onClick={onConfirm}>
            {pending ? t('Deleting…') : t('Delete supplier')}
          </Button>
        </div>
      </div>
    </Modal>
  );
}
