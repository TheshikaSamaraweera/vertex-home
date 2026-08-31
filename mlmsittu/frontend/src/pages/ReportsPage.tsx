import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthContext';
import { useCategories, useLocations } from '../api/queries';
import {
  downloadCsv,
  useSalesReport,
  useStockReport,
  type DateRange,
} from '../api/reports';
import {
  Badge,
  Button,
  Card,
  EmptyState,
  ErrorBanner,
  Field,
  Input,
  money,
  PageHeader,
  Select,
  Spinner,
  Table,
  TableWrap,
  Td,
  Th,
} from '../components/ui';

/**
 * Stock and sales reporting (P6-01, P6-02).
 *
 * Every figure here is meant to be cross-checkable against direct SQL — that is Gate 6's first
 * condition — so the screen shows what was filtered, not just the result. A total with no visible
 * filter is a number nobody can reproduce.
 */
export function ReportsPage() {
  const { t } = useTranslation();
  const { hasRole } = useAuth();
  const canSeeMoney = hasRole('FINANCE_OFFICER', 'SUPER_ADMIN', 'SUPPORT_AGENT');

  return (
    <>
      <PageHeader
        title={t('Reports')}
        description={t('Figures come straight from the database with no caching in between, so anything here can be checked against a query.')}
      />
      <div className="flex flex-col gap-5">
        <StockReportCard />
        {canSeeMoney && <SalesReportCard />}
      </div>
    </>
  );
}

// ================================================================== stock (P6-01)

function StockReportCard() {
  const { t } = useTranslation();
  const locations = useLocations();
  const categories = useCategories();

  const [filters, setFilters] = useState({
    locationId: '',
    categoryId: '',
    belowReorderOnly: false,
    includeInactive: false,
  });

  const report = useStockReport({
    locationId: filters.locationId || undefined,
    categoryId: filters.categoryId || undefined,
    belowReorderOnly: filters.belowReorderOnly,
    includeInactive: filters.includeInactive,
  });

  const [exportError, setExportError] = useState<unknown>(null);

  return (
    <Card
      title={t('Stock position')}
      subtitle={t('Value is on hand × unit cost. “Below reorder” compares available stock, matching the alert rule exactly.')}
      actions={
        <Button
          size="sm"
          onClick={() =>
            void downloadCsv('/api/v1/reports/stock.csv', filters, 'stock-report.csv').catch(
              setExportError,
            )
          }
        >
          {t('Export CSV')}
        </Button>
      }
    >
      <div className="grid gap-3 border-b border-rule p-4 sm:grid-cols-2 lg:grid-cols-4">
        <Field label={t('Location')}>
          <Select
            value={filters.locationId}
            onChange={(event) => setFilters({ ...filters, locationId: event.target.value })}
          >
            <option value="">{t('All locations')}</option>
            {(locations.data ?? []).map((location) => (
              <option key={location.id} value={location.id ?? ''}>
                {location.name}
              </option>
            ))}
          </Select>
        </Field>
        <Field label={t('Category')}>
          <Select
            value={filters.categoryId}
            onChange={(event) => setFilters({ ...filters, categoryId: event.target.value })}
          >
            <option value="">{t('All categories')}</option>
            {(categories.data ?? []).map((category) => (
              <option key={category.id} value={category.id ?? ''}>
                {category.name}
              </option>
            ))}
          </Select>
        </Field>
        <Field label={t('Show')}>
          <Select
            value={filters.belowReorderOnly ? 'low' : 'all'}
            onChange={(event) =>
              setFilters({ ...filters, belowReorderOnly: event.target.value === 'low' })
            }
          >
            <option value="all">{t('Everything')}</option>
            <option value="low">{t('Below reorder level only')}</option>
          </Select>
        </Field>
        <Field label={t('Deactivated items')}>
          <Select
            value={filters.includeInactive ? 'include' : 'exclude'}
            onChange={(event) =>
              setFilters({ ...filters, includeInactive: event.target.value === 'include' })
            }
          >
            <option value="exclude">{t('Hide')}</option>
            <option value="include">{t('Include — they still hold stock')}</option>
          </Select>
        </Field>
      </div>

      {exportError != null && (
        <div className="p-4 pb-0">
          <ErrorBanner error={exportError} />
        </div>
      )}

      {report.isLoading ? (
        <Spinner />
      ) : report.error ? (
        <div className="p-4">
          <ErrorBanner error={report.error} onRetry={() => void report.refetch()} />
        </div>
      ) : (report.data?.rows ?? []).length === 0 ? (
        <EmptyState message={t('Nothing matches those filters.')} />
      ) : (
        <>
          <div className="nums grid gap-3 border-b border-rule p-4 sm:grid-cols-2 lg:grid-cols-5">
            <Stat label={t('Rows')} value={String(report.data?.itemCount ?? 0)} />
            <Stat label={t('On hand')} value={String(report.data?.totalOnHand ?? 0)} />
            <Stat label={t('Reserved')} value={String(report.data?.totalReserved ?? 0)} />
            <Stat label={t('Stock value')} value={money(report.data?.totalValue)} />
            <Stat
              label={t('Below reorder')}
              value={String(report.data?.belowReorderCount ?? 0)}
              tone={(report.data?.belowReorderCount ?? 0) > 0 ? 'warn' : undefined}
            />
          </div>

          <TableWrap>
            <Table>
              <thead>
                <tr>
                  <Th>{t('Item code')}</Th>
                  <Th>{t('Item')}</Th>
                  <Th>{t('Category')}</Th>
                  <Th>{t('Location')}</Th>
                  <Th align="right">{t('On hand')}</Th>
                  <Th align="right">{t('Reserved')}</Th>
                  <Th align="right">{t('Available')}</Th>
                  <Th align="right">{t('Reorder at')}</Th>
                  <Th align="right">{t('Value')}</Th>
                </tr>
              </thead>
              <tbody>
                {(report.data?.rows ?? []).map((row) => (
                  <tr
                    key={`${row.itemId}-${row.locationId}`}
                    className={row.belowReorder ? 'bg-warnsoft/30' : 'hover:bg-panel2'}
                  >
                    <Td className="font-mono text-xs">{row.sku}</Td>
                    <Td className="text-ink">{row.name}</Td>
                    <Td className="text-xs">{row.categoryName ?? '—'}</Td>
                    <Td className="text-xs">{row.locationName}</Td>
                    <Td align="right">{row.onHand}</Td>
                    <Td align="right" className={row.reserved ? 'text-warn' : undefined}>
                      {row.reserved}
                    </Td>
                    <Td align="right" className="font-semibold text-ink">
                      {row.available}
                    </Td>
                    <Td align="right" className="text-ink3">
                      {row.reorderLevel === 0 ? '—' : row.reorderLevel}
                    </Td>
                    <Td align="right">{money(row.stockValue)}</Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          </TableWrap>
        </>
      )}
    </Card>
  );
}

// ================================================================== sales (P6-02)

function SalesReportCard() {
  const { t } = useTranslation();

  const today = new Date().toISOString().slice(0, 10);
  const monthAgo = new Date(Date.now() - 30 * 86_400_000).toISOString().slice(0, 10);

  const [range, setRange] = useState<DateRange>({ from: monthAgo, to: today });
  const report = useSalesReport(range);
  const [exportError, setExportError] = useState<unknown>(null);

  const peak = Math.max(1, ...(report.data?.daily ?? []).map((day) => Number(day.netValue ?? 0)));

  return (
    <Card
      title={t('Sales')}
      subtitle={t('Fulfilled orders only, dated by the day the goods left — the same set the invoice register covers.')}
      actions={
        <Button
          size="sm"
          onClick={() =>
            void downloadCsv(
              '/api/v1/reports/sales.csv',
              { from: range.from, to: range.to },
              'sales-report.csv',
            ).catch(setExportError)
          }
        >
          {t('Export CSV')}
        </Button>
      }
    >
      <div className="grid gap-3 border-b border-rule p-4 sm:grid-cols-2 lg:grid-cols-4">
        <Field label={t('From')}>
          <Input
            type="date"
            value={range.from ?? ''}
            onChange={(event) => setRange({ ...range, from: event.target.value })}
          />
        </Field>
        <Field label={t('To')}>
          <Input
            type="date"
            value={range.to ?? ''}
            onChange={(event) => setRange({ ...range, to: event.target.value })}
          />
        </Field>
        <div className="flex items-end gap-2 sm:col-span-2">
          <Button size="sm" onClick={() => setRange({ from: monthAgo, to: today })}>
            {t('Last 30 days')}
          </Button>
          <Button
            size="sm"
            onClick={() =>
              setRange({
                from: new Date(Date.now() - 365 * 86_400_000).toISOString().slice(0, 10),
                to: today,
              })
            }
          >
            {t('Last year')}
          </Button>
          <Button size="sm" onClick={() => setRange({ from: today, to: today })}>
            {t('Today')}
          </Button>
        </div>
      </div>

      {exportError != null && (
        <div className="p-4 pb-0">
          <ErrorBanner error={exportError} />
        </div>
      )}

      {report.isLoading ? (
        <Spinner />
      ) : report.error ? (
        <div className="p-4">
          <ErrorBanner error={report.error} onRetry={() => void report.refetch()} />
        </div>
      ) : (
        <>
          <div className="nums grid gap-3 border-b border-rule p-4 sm:grid-cols-2 lg:grid-cols-5">
            <Stat label={t('Orders')} value={String(report.data?.orderCount ?? 0)} />
            <Stat label={t('Gross')} value={money(report.data?.grossValue)} />
            <Stat label={t('Discount')} value={money(report.data?.discountValue)} />
            <Stat label={t('Net')} value={money(report.data?.netValue)} tone="brand" />
            <Stat label={t('Average order')} value={money(report.data?.averageOrderValue)} />
          </div>

          {/* An empty range is a valid answer, so it gets an empty state and not an error. */}
          {(report.data?.orderCount ?? 0) === 0 ? (
            <EmptyState
              message={t('No sales in that range.')}
              hint={t('That is an answer, not a problem — widen the dates to see more.')}
            />
          ) : (
            <div className="grid gap-0 lg:grid-cols-2">
              <div className="border-b border-rule p-4 lg:border-r lg:border-b-0">
                <p className="mb-3 text-xs font-semibold tracking-wide text-ink2 uppercase">
                  {t('By day')}
                </p>
                <ul className="flex flex-col gap-1">
                  {(report.data?.daily ?? []).map((day) => (
                    <li key={day.day} className="flex items-center gap-2 text-xs">
                      <span className="w-20 shrink-0 text-ink3">{day.day}</span>
                      {/* A bar rather than a chart library: one dimension, one comparison. */}
                      <span className="h-3 flex-1 rounded-sm bg-panel2">
                        <span
                          className="block h-3 rounded-sm bg-brand"
                          style={{ width: `${(Number(day.netValue ?? 0) / peak) * 100}%` }}
                        />
                      </span>
                      <span className="nums w-24 shrink-0 text-right text-ink2">
                        {money(day.netValue)}
                      </span>
                      <span className="nums w-8 shrink-0 text-right text-ink3">
                        {day.orderCount}
                      </span>
                    </li>
                  ))}
                </ul>
              </div>

              <div className="p-4">
                <p className="mb-3 text-xs font-semibold tracking-wide text-ink2 uppercase">
                  {t('Top products')}
                </p>
                <TableWrap>
                  <Table>
                    <thead>
                      <tr>
                        <Th>{t('Product')}</Th>
                        <Th align="right">{t('Qty')}</Th>
                        <Th align="right">{t('Net')}</Th>
                      </tr>
                    </thead>
                    <tbody>
                      {(report.data?.topProducts ?? []).slice(0, 10).map((product) => (
                        <tr key={product.productId}>
                          <Td className="text-ink">{product.description}</Td>
                          <Td align="right">{product.quantity}</Td>
                          <Td align="right">{money(product.netValue)}</Td>
                        </tr>
                      ))}
                    </tbody>
                  </Table>
                </TableWrap>
                <p className="mt-2 text-[11px] text-ink3">
                  {/* The screen shows the top ten; the export and the underlying figures carry
                      every product, which is what makes the reconciliation claim true. */}
                  {t('Showing the top 10 of {{total}}. Line values are prorated by each order’s discount, so the full set adds up to the net figure above.', {
                    total: report.data?.topProducts?.length ?? 0,
                  })}
                </p>
              </div>
            </div>
          )}
        </>
      )}
    </Card>
  );
}

function Stat({
  label,
  value,
  tone,
}: {
  label: string;
  value: string;
  tone?: 'brand' | 'warn';
}) {
  return (
    <div>
      <p className="text-[10px] font-semibold tracking-wider text-ink3 uppercase">{label}</p>
      {tone ? (
        <Badge tone={tone}>{value}</Badge>
      ) : (
        <p className="text-lg font-semibold text-ink">{value}</p>
      )}
    </div>
  );
}
