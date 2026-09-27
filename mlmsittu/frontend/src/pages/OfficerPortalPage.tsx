import { useTranslation } from 'react-i18next';
import {
  asPercent,
  useMyAssignedCustomers,
  useMyOfficerRecord,
} from '../api/officers';
import { expiryStatus } from '../lib/expiry';
import { StageDots } from './MarketingOfficersPage';
import {
  Badge,
  Card,
  PageHeader,
  Spinner,
  Table,
  TableWrap,
  Td,
  Th,
} from '../components/ui';

/**
 * A marketing officer's own screen.
 *
 * <p>Their customers, how far each has got, and what each has earned them. Nothing else — an
 * officer is not staff: they approve nothing, see no NIC images, and move no stock.
 *
 * <p>Every query here is scoped to the signed-in officer server-side, with no id parameter
 * anywhere. There is no shape of any call on this page that returns another officer's customers.
 */
export function OfficerPortalPage() {
  const { t } = useTranslation();
  const me = useMyOfficerRecord();
  const customers = useMyAssignedCustomers();

  if (me.isLoading || customers.isLoading) return <Spinner />;

  const rows = customers.data ?? [];
  const issued = rows.filter((row) => row.packIssued).length;

  return (
    <>
      <PageHeader
        title={t('My customers')}
        description={t('The customers assigned to you, and how far each has got.')}
      />

      <div className="mb-5 grid gap-4 sm:grid-cols-3">
        <Figure label={t('Customers')} value={String(rows.length)} />
        <Figure label={t('Packs issued')} value={String(issued)} />
        <Figure
          label={t('Earned at {{rate}}', { rate: asPercent(me.data?.commissionRate ?? 0) })}
          value={money(me.data?.earned ?? 0)}
          strong
        />
      </div>

      <Card title={t('Assigned to you')}>
        {rows.length === 0 ? (
          <p className="px-5 py-12 text-center text-sm text-ink3">
            {t('Nobody is assigned to you yet. The office decides who you look after.')}
          </p>
        ) : (
          <TableWrap>
            <Table>
              <thead>
                <tr>
                  <Th>{t('Business ID')}</Th>
                  <Th>{t('Customer')}</Th>
                  <Th>{t('Level')}</Th>
                  <Th>{t('Membership')}</Th>
                  <Th>{t('Item pack')}</Th>
                  <Th align="right">{t('You earned')}</Th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => {
                  const expiry = expiryStatus(row.expiresAt);
                  return (
                    <tr key={row.distributorId}>
                      <Td className="font-mono text-xs text-brand">{row.businessId ?? '—'}</Td>
                      <Td className="text-ink">{row.fullName}</Td>
                      <Td>
                        <StageDots done={row.stagesCompleted} total={row.totalStages} />
                      </Td>
                      <Td>
                        {expiry.band === 'none' ? (
                          <span className="text-xs text-ink3">—</span>
                        ) : (
                          <span
                            className={`inline-flex rounded-full border px-2 py-0.5 text-[11px] whitespace-nowrap ${expiry.classes}`}
                          >
                            {expiry.label}
                          </span>
                        )}
                      </Td>
                      <Td className="text-xs">
                        {row.packName ?? '—'}{' '}
                        {row.packIssued ? (
                          <Badge tone="ok">{t('issued')}</Badge>
                        ) : (
                          <span className="text-ink3">{t('not yet')}</span>
                        )}
                      </Td>
                      <Td
                        align="right"
                        className={row.earned > 0 ? 'font-semibold text-ok' : 'text-ink3'}
                      >
                        {row.earned > 0 ? money(row.earned) : '—'}
                      </Td>
                    </tr>
                  );
                })}
              </tbody>
            </Table>
          </TableWrap>
        )}
      </Card>

      <p className="mt-3 text-xs text-ink3">
        {/* Said plainly so nobody reads the column as a statement of account. */}
        {t('A customer earns you {{rate}} of their item pack when it is handed over — not before. These figures are for information; the office settles up separately.', {
          rate: asPercent(me.data?.commissionRate ?? 0),
        })}
      </p>
    </>
  );
}

function Figure({ label, value, strong }: { label: string; value: string; strong?: boolean }) {
  return (
    <div className="rounded-xl border-2 border-rulestrong bg-panel p-4 shadow-card">
      <p className="text-[10.5px] font-bold tracking-wider text-ink2 uppercase">{label}</p>
      <p className={`nums mt-1 text-2xl font-bold ${strong ? 'text-ok' : 'text-ink'}`}>{value}</p>
    </div>
  );
}

function money(value: number): string {
  return value.toLocaleString(undefined, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
}
