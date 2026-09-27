import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import {
  Card,
  Field,
  Input,
  PageHeader,
  Spinner,
  Table,
  TableWrap,
  Td,
  Th,
} from '../components/ui';

type IssuedPack = {
  entitlementId: string;
  issuedAt: string;
  businessId: string | null;
  customerName: string;
  packCode: string;
  packName: string;
  sellingPrice: number;
  actualCost: number;
  grossProfit: number;
  officerName: string | null;
  commissionRate: number;
  commission: number;
  netProfit: number;
};

type CostAnalysis = {
  rows: IssuedPack[];
  totals: {
    packsIssued: number;
    sellingPrice: number;
    actualCost: number;
    grossProfit: number;
    commission: number;
    netProfit: number;
  };
};

/**
 * What the reward packs earned and what they cost.
 *
 * <p>Issued packs only. A pack somebody is merely eligible for has cost nothing and earned
 * nothing — no stock has moved and nobody has been credited — so counting it would be counting an
 * intention rather than a transaction.
 *
 * <p>Gross and net are both shown. A pack that looks unprofitable is either expensive goods or a
 * large commission, and one number cannot say which.
 */
export function CostAnalysisPage() {
  const { t } = useTranslation();

  const [from, setFrom] = useState(() => isoDate(daysAgo(90)));
  const [to, setTo] = useState(() => isoDate(new Date()));

  const { data, isLoading } = useQuery({
    queryKey: ['cost-analysis', from, to],
    queryFn: () =>
      api.get<CostAnalysis>('/api/v1/reports/cost-analysis', {
        from: `${from}T00:00:00Z`,
        // Through the end of the chosen day, not its start — a report to the 30th that stops at
        // midnight on the 30th silently omits everything issued that day.
        to: `${to}T23:59:59Z`,
      }),
  });

  return (
    <>
      <PageHeader
        title={t('Cost analysis')}
        description={t('Reward packs that have actually been handed over: what each earned, what it cost, and what was left.')}
      />

      <Card className="mb-5">
        <div className="grid gap-4 p-4 sm:grid-cols-2 lg:grid-cols-4">
          <Field label={t('From')}>
            <Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
          </Field>
          <Field label={t('To')}>
            <Input type="date" value={to} onChange={(e) => setTo(e.target.value)} />
          </Field>
        </div>
      </Card>

      {isLoading ? (
        <Spinner />
      ) : (
        <>
          <div className="mb-5 grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
            <Figure label={t('Packs issued')} value={String(data?.totals.packsIssued ?? 0)} plain />
            <Figure label={t('Pack value')} value={money(data?.totals.sellingPrice)} />
            <Figure label={t('Actual cost')} value={money(data?.totals.actualCost)} />
            <Figure label={t('Commission')} value={money(data?.totals.commission)} />
            <Figure
              label={t('Net profit')}
              value={money(data?.totals.netProfit)}
              // The one figure worth colouring: a loss on the packs is the thing this page exists
              // to surface, and a negative number in the same ink as the rest is easy to read past.
              tone={(data?.totals.netProfit ?? 0) < 0 ? 'danger' : 'ok'}
            />
          </div>

          <Card title={t('Issued packs')}>
            <TableWrap>
              <Table>
                <thead>
                  <tr>
                    <Th>{t('Issued')}</Th>
                    <Th>{t('Business ID')}</Th>
                    <Th>{t('Customer')}</Th>
                    <Th>{t('Item pack')}</Th>
                    <Th align="right">{t('Selling price')}</Th>
                    <Th align="right">{t('Actual cost')}</Th>
                    <Th align="right">{t('Gross profit')}</Th>
                    <Th>{t('Marketing officer')}</Th>
                    <Th align="right">{t('Commission')}</Th>
                    <Th align="right">{t('Net profit')}</Th>
                  </tr>
                </thead>
                <tbody>
                  {(data?.rows ?? []).map((row) => (
                    <tr key={row.entitlementId}>
                      <Td className="text-xs whitespace-nowrap">
                        {new Date(row.issuedAt).toLocaleDateString()}
                      </Td>
                      <Td className="font-mono text-xs text-brand">{row.businessId ?? '—'}</Td>
                      <Td className="text-ink">{row.customerName}</Td>
                      <Td className="text-xs">
                        {row.packName}
                        <span className="ml-1.5 font-mono text-ink3">{row.packCode}</span>
                      </Td>
                      <Td align="right">{money(row.sellingPrice)}</Td>
                      <Td align="right">{money(row.actualCost)}</Td>
                      <Td align="right" className={row.grossProfit < 0 ? 'text-danger' : ''}>
                        {money(row.grossProfit)}
                      </Td>
                      <Td className="text-xs">
                        {row.officerName ?? <span className="text-ink3">{t('none')}</span>}
                      </Td>
                      <Td align="right" className="text-xs">
                        {row.officerName ? (
                          <>
                            {money(row.commission)}
                            <span className="ml-1 text-ink3">
                              ({(row.commissionRate * 100).toFixed(2)}%)
                            </span>
                          </>
                        ) : (
                          '—'
                        )}
                      </Td>
                      <Td
                        align="right"
                        className={
                          'font-semibold ' + (row.netProfit < 0 ? 'text-danger' : 'text-ink')
                        }
                      >
                        {money(row.netProfit)}
                      </Td>
                    </tr>
                  ))}
                </tbody>
              </Table>
            </TableWrap>

            {(data?.rows ?? []).length === 0 && (
              <p className="px-5 py-12 text-center text-sm text-ink3">
                {t('No packs were issued in this period. Only packs actually handed over appear here.')}
              </p>
            )}
          </Card>

          <p className="mt-3 text-xs text-ink3">
            {t('Cost is the sum of each item’s unit cost times its quantity in the pack, read at the moment this report runs. Changing an item’s cost changes these figures for past packs too.')}
          </p>
        </>
      )}
    </>
  );
}

function Figure({
  label,
  value,
  tone,
  plain,
}: {
  label: string;
  value: string;
  tone?: 'ok' | 'danger';
  plain?: boolean;
}) {
  const colour = tone === 'danger' ? 'text-danger' : tone === 'ok' ? 'text-ok' : 'text-ink';
  return (
    <div className="rounded-xl border-2 border-rulestrong bg-panel p-4 shadow-card">
      <p className="text-[10.5px] font-bold tracking-wider text-ink2 uppercase">{label}</p>
      <p className={`mt-1 text-2xl font-bold ${plain ? 'text-ink' : `nums ${colour}`}`}>{value}</p>
    </div>
  );
}

function money(value: number | undefined): string {
  return (value ?? 0).toLocaleString(undefined, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
}

function daysAgo(days: number): Date {
  const date = new Date();
  date.setDate(date.getDate() - days);
  return date;
}

function isoDate(date: Date): string {
  return date.toISOString().slice(0, 10);
}
