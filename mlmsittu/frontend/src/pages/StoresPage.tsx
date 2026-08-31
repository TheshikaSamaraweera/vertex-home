import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { useCreateStore, useLocations, useSetStoreActive, useUpdateStore } from '../api/queries';
import type { LocationSummary } from '../api/types';
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
 * Stores — the physical places stock sits.
 *
 * Added alongside the receiving split, because that is what made a second store meaningful.
 * "Which store did this delivery go into?" is not a question worth asking when the answer can only
 * ever be "Main Warehouse", and until now there was no way to create another one.
 *
 * There is no delete. Stock rows, movements and receipts all point at a store, and removing one
 * would orphan history that is supposed to be permanent. Deactivating takes it out of every picker
 * and leaves what is already there alone, which is what "we don't use that place any more"
 * actually means.
 */
export function StoresPage() {
  const { t } = useTranslation();
  const { hasRole } = useAuth();
  const canWrite = hasRole('INVENTORY_CLERK');

  const stores = useLocations();
  const setActive = useSetStoreActive();

  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<LocationSummary | null>(null);

  return (
    <>
      <PageHeader
        title={t('Stores')}
        description={t('Where stock physically sits. Deliveries are assigned to one of these.')}
        actions={
          canWrite && (
            <Button variant="primary" size="sm" onClick={() => setCreating(true)}>
              {t('New store')}
            </Button>
          )
        }
      />

      {setActive.error != null && (
        <div className="mb-4">
          <ErrorBanner error={setActive.error} />
        </div>
      )}

      <Card
        title={t('Store list')}
        subtitle={t('{{count}} shown', { count: (stores.data ?? []).length })}
      >
        {stores.isLoading ? (
          <Spinner />
        ) : stores.error ? (
          <div className="p-4">
            <ErrorBanner error={stores.error} onRetry={() => void stores.refetch()} />
          </div>
        ) : (stores.data ?? []).length === 0 ? (
          <EmptyState message={t('No stores yet.')} />
        ) : (
          <TableWrap>
            <Table>
              <thead>
                <tr>
                  <Th>{t('Code')}</Th>
                  <Th>{t('Name')}</Th>
                  <Th>{t('Location')}</Th>
                  <Th>{t('Status')}</Th>
                  <Th>{''}</Th>
                </tr>
              </thead>
              <tbody>
                {(stores.data ?? []).map((store) => (
                  <tr key={store.id} className={store.active ? 'hover:bg-panel2' : 'opacity-60'}>
                    <Td className="font-mono text-xs">{store.code}</Td>
                    <Td className="text-ink">
                      {store.name}
                      {store.isDefault && (
                        <span className="ml-2">
                          <Badge tone="brand">{t('default')}</Badge>
                        </span>
                      )}
                    </Td>
                    <Td className="text-xs">{store.address ?? '—'}</Td>
                    <Td>
                      {store.active ? (
                        <Badge tone="ok">{t('active')}</Badge>
                      ) : (
                        <Badge tone="danger">{t('deactivated')}</Badge>
                      )}
                    </Td>
                    <Td>
                      <div className="flex justify-end gap-1">
                        {/* Open to everyone who can see the screen — knowing what is in a store
                            is a reading question, not an editing one. */}
                        <Link
                          to={`/stores/${store.id}`}
                          className="rounded-md border border-rule bg-panel px-2.5 py-1 text-xs font-medium text-ink2 transition-colors hover:border-brand hover:text-brand"
                        >
                          {t('View store')}
                        </Link>
                        {canWrite && (
                          <>
                            <Button size="sm" onClick={() => setEditing(store)}>
                              {t('Edit')}
                            </Button>
                          {/* The default store has no toggle at all. The server refuses it with
                              DEFAULT_STORE_REQUIRED; offering a button that always fails is worse
                              than not offering one. */}
                            {!store.isDefault && (
                              <Button
                                size="sm"
                                variant="ghost"
                                disabled={setActive.isPending}
                                onClick={() =>
                                  store.id &&
                                  setActive.mutate({ id: store.id, active: !store.active })
                                }
                              >
                                {store.active ? t('Deactivate') : t('Reactivate')}
                              </Button>
                            )}
                          </>
                        )}
                      </div>
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          </TableWrap>
        )}
      </Card>

      {creating && <StoreModal onClose={() => setCreating(false)} />}
      {editing && <StoreModal existing={editing} onClose={() => setEditing(null)} />}
    </>
  );
}

/** One form for create and edit. The code is fixed once stock points at it. */
function StoreModal({ existing, onClose }: { existing?: LocationSummary; onClose: () => void }) {
  const { t } = useTranslation();
  const create = useCreateStore();
  const update = useUpdateStore();
  const mutation = existing ? update : create;

  const [code, setCode] = useState(existing?.code ?? '');
  const [name, setName] = useState(existing?.name ?? '');
  const [address, setAddress] = useState(existing?.address ?? '');

  return (
    <Modal title={existing ? t('Edit store') : t('New store')} onClose={onClose}>
      <form
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault();
          const body = { code, name, address: address || undefined };
          if (existing?.id) {
            update.mutate({ id: existing.id, body }, { onSuccess: onClose });
          } else {
            create.mutate(body, { onSuccess: onClose });
          }
        }}
      >
        <Field
          label={t('Code')}
          hint={
            existing
              ? t('The code cannot change — stock and receipts point at it')
              : t('Short and stable, e.g. KANDY')
          }
        >
          <Input
            required
            autoFocus={!existing}
            disabled={Boolean(existing)}
            value={code}
            onChange={(event) => setCode(event.target.value.toUpperCase())}
            placeholder="KANDY"
          />
        </Field>

        <Field label={t('Name')}>
          <Input
            required
            autoFocus={Boolean(existing)}
            value={name}
            onChange={(event) => setName(event.target.value)}
            placeholder={t('Kandy branch store')}
          />
        </Field>

        <Field label={t('Location')} hint={t('Optional — where a driver would take a delivery')}>
          <Input
            value={address}
            onChange={(event) => setAddress(event.target.value)}
            placeholder={t('Peradeniya Road, Kandy')}
          />
        </Field>

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
                : t('Create store')}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
