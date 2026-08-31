import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  downloadCsv,
  useCustomerAnalytics,
  useCustomerTreemap,
  type CustomerAnalyticsRow,
  type DateRange,
  type TreemapNode,
} from '../api/reports';
import { Treemap } from '../components/Treemap';
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
  Spinner,
  Table,
  TableWrap,
  Td,
  Th,
} from '../components/ui';

/**
 * Customer analytics (P6-03, P6-04).
 *
 * The treemap and the table show the same numbers, and the table is the one that can be read
 * precisely. Architecture §6.4 is blunt about why: treemaps are poor for exact comparison and
 * unusable on a phone, **so the table is the default below 768 px** — not a fallback you have to
 * find, the thing that loads.
 */
export function CustomerAnalyticsPage() {
  const { t } = useTranslation();

  const today = new Date().toISOString().slice(0, 10);
  const yearAgo = new Date(Date.now() - 365 * 86_400_000).toISOString().slice(0, 10);
  const [range, setRange] = useState<DateRange>({ from: yearAgo, to: today });

  const narrow = useIsNarrow();
  // Chosen once from the viewport, then owned by the user. Flipping the view out from under
  // somebody because they rotated a tablet would be worse than starting on the wrong one.
  const [view, setView] = useState<'chart' | 'table'>(() => (narrow ? 'table' : 'chart'));

  return (
    <>
      <PageHeader
        title={t('Buyer analytics')}
        description={t('Who buys what. Rectangle area is value and colour is how long ago they last ordered.')}
        actions={
          <div className="flex items-center gap-1 rounded border border-rule p-0.5" role="group">
            <Button
              size="sm"
              variant={view === 'chart' ? 'primary' : 'ghost'}
              onClick={() => setView('chart')}
              aria-pressed={view === 'chart'}
            >
              {t('Treemap')}
            </Button>
            <Button
              size="sm"
              variant={view === 'table' ? 'primary' : 'ghost'}
              onClick={() => setView('table')}
              aria-pressed={view === 'table'}
            >
              {t('Table')}
            </Button>
          </div>
        }
      />

      {view === 'chart' ? (
        <TreemapCard range={range} onRangeChange={setRange} narrow={narrow} />
      ) : (
        <CustomerTableCard />
      )}
    </>
  );
}

/** One listener, not one per component, and it survives a resize rather than only a reload. */
function useIsNarrow(): boolean {
  const [narrow, setNarrow] = useState(
    () => typeof window !== 'undefined' && window.matchMedia('(max-width: 767px)').matches,
  );

  useEffect(() => {
    const query = window.matchMedia('(max-width: 767px)');
    const listener = (event: MediaQueryListEvent) => setNarrow(event.matches);
    query.addEventListener('change', listener);
    return () => query.removeEventListener('change', listener);
  }, []);

  return narrow;
}

// ================================================================== treemap (P6-04)

function TreemapCard({
  range,
  onRangeChange,
  narrow,
}: {
  range: DateRange;
  onRangeChange: (range: DateRange) => void;
  narrow: boolean;
}) {
  const { t } = useTranslation();
  const treemap = useCustomerTreemap(range);
  const [selected, setSelected] = useState<{
    name: string;
    category: string;
    value: number;
    daysSinceLastOrder: number | null;
  } | null>(null);

  const leafCount = useMemo(() => countLeaves(treemap.data), [treemap.data]);

  return (
    <Card
      title={t('Sales by category and buyer')}
      subtitle={t('Areas sum to the sales report’s net total for the same range — line values are prorated by each order’s discount.')}
    >
      <div className="grid gap-3 border-b border-rule p-4 sm:grid-cols-3">
        <Field label={t('From')}>
          <Input
            type="date"
            value={range.from ?? ''}
            onChange={(event) => onRangeChange({ ...range, from: event.target.value })}
          />
        </Field>
        <Field label={t('To')}>
          <Input
            type="date"
            value={range.to ?? ''}
            onChange={(event) => onRangeChange({ ...range, to: event.target.value })}
          />
        </Field>
        <div className="flex items-end">
          <p className="text-xs text-ink3">
            {t('{{count}} customer blocks', { count: leafCount })}
          </p>
        </div>
      </div>

      {treemap.isLoading ? (
        <Spinner />
      ) : treemap.error ? (
        <div className="p-4">
          <ErrorBanner error={treemap.error} onRetry={() => void treemap.refetch()} />
        </div>
      ) : leafCount === 0 ? (
        <EmptyState
          message={t('No sales in that range to draw.')}
          hint={t('Fulfil an order, or widen the dates.')}
        />
      ) : (
        <div className="p-4">
          <Treemap
            data={treemap.data as TreemapNode}
            height={narrow ? 320 : 460}
            onSelect={setSelected}
          />

          {selected && (
            <div className="mt-4 rounded border border-rule bg-panel2 px-3 py-2.5">
              <p className="text-sm font-semibold text-ink">{selected.name}</p>
              <p className="nums mt-0.5 text-xs text-ink2">
                {selected.category} · {money(selected.value)} ·{' '}
                {selected.daysSinceLastOrder === null
                  ? t('no recent order')
                  : t('{{days}} days since last order', { days: selected.daysSinceLastOrder })}
              </p>
            </div>
          )}

          <p className="mt-4 text-[11px] text-ink3">
            {t('People compare areas badly — two rectangles differing by a fifth look the same. Use the table when the exact number matters.')}
          </p>
        </div>
      )}
    </Card>
  );
}

function countLeaves(node: TreemapNode | undefined): number {
  if (!node) return 0;
  if (!node.children || node.children.length === 0) return 1;
  return node.children.reduce((sum, child) => sum + countLeaves(child), 0);
}

// ================================================================== table (P6-03)

type SortKey = 'name' | 'city' | 'orderCount' | 'totalValue' | 'daysSinceLastOrder';

function CustomerTableCard() {
  const { t } = useTranslation();
  const [includeInactive, setIncludeInactive] = useState(false);
  const [filter, setFilter] = useState('');
  const [sort, setSort] = useState<{ key: SortKey; descending: boolean }>({
    key: 'totalValue',
    descending: true,
  });

  const customers = useCustomerAnalytics(includeInactive);
  const [exportError, setExportError] = useState<unknown>(null);

  /**
   * Sorting and filtering run in the browser, because the whole set is already here — P6-03 asks
   * for all five hundred rows in one response so the load time can be compared against Phase 7's
   * pagination. When the cursor lands, both move to the server with it.
   */
  const rows = useMemo(() => {
    const term = filter.trim().toLowerCase();
    const matching = (customers.data ?? []).filter(
      (row) =>
        !term ||
        row.name?.toLowerCase().includes(term) ||
        row.code?.toLowerCase().includes(term) ||
        row.city?.toLowerCase().includes(term),
    );

    const direction = sort.descending ? -1 : 1;
    return [...matching].sort((a, b) => direction * compare(a, b, sort.key));
  }, [customers.data, filter, sort]);

  const toggleSort = (key: SortKey) =>
    setSort((current) =>
      current.key === key
        ? { key, descending: !current.descending }
        : { key, descending: key !== 'name' && key !== 'city' },
    );

  return (
    <Card
      title={t('Buyers by value')}
      subtitle={t('Everyone, including those who have never ordered — that is a question worth being able to ask.')}
      actions={
        <>
          <Input
            className="w-52"
            placeholder={t('Filter name, code or city…')}
            value={filter}
            onChange={(event) => setFilter(event.target.value)}
          />
          <Button size="sm" onClick={() => setIncludeInactive(!includeInactive)}>
            {includeInactive ? t('Hide deactivated') : t('Show deactivated')}
          </Button>
          <Button
            size="sm"
            onClick={() =>
              void downloadCsv(
                '/api/v1/reports/customers.csv',
                { includeInactive },
                'customers.csv',
              ).catch(setExportError)
            }
          >
            {t('Export CSV')}
          </Button>
        </>
      }
    >
      {exportError != null && (
        <div className="p-4 pb-0">
          <ErrorBanner error={exportError} />
        </div>
      )}

      {customers.isLoading ? (
        <Spinner label={t('Loading every buyer…')} />
      ) : customers.error ? (
        <div className="p-4">
          <ErrorBanner error={customers.error} onRetry={() => void customers.refetch()} />
        </div>
      ) : rows.length === 0 ? (
        <EmptyState message={t('Nothing matches.')} />
      ) : (
        <>
          <p className="border-b border-rule px-4 py-2 text-xs text-ink3">
            {t('{{shown}} of {{total}} customers', {
              shown: rows.length,
              total: customers.data?.length ?? 0,
            })}
          </p>
          <TableWrap>
            <Table>
              <thead>
                <tr>
                  <Th>{t('Code')}</Th>
                  <SortableTh
                    label={t('Name')}
                    active={sort.key === 'name'}
                    descending={sort.descending}
                    onClick={() => toggleSort('name')}
                  />
                  <SortableTh
                    label={t('City')}
                    active={sort.key === 'city'}
                    descending={sort.descending}
                    onClick={() => toggleSort('city')}
                  />
                  <SortableTh
                    label={t('Orders')}
                    align="right"
                    active={sort.key === 'orderCount'}
                    descending={sort.descending}
                    onClick={() => toggleSort('orderCount')}
                  />
                  <SortableTh
                    label={t('Total value')}
                    align="right"
                    active={sort.key === 'totalValue'}
                    descending={sort.descending}
                    onClick={() => toggleSort('totalValue')}
                  />
                  <SortableTh
                    label={t('Last order')}
                    align="right"
                    active={sort.key === 'daysSinceLastOrder'}
                    descending={sort.descending}
                    onClick={() => toggleSort('daysSinceLastOrder')}
                  />
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.customerId} className={row.active ? 'hover:bg-panel2' : 'opacity-60'}>
                    <Td className="font-mono text-xs">{row.code}</Td>
                    <Td className="text-ink">{row.name}</Td>
                    <Td className="text-xs">{row.city ?? '—'}</Td>
                    <Td align="right">{row.orderCount}</Td>
                    <Td align="right" className="font-semibold text-ink">
                      {money(row.totalValue)}
                    </Td>
                    <Td align="right">
                      {row.daysSinceLastOrder === null || row.daysSinceLastOrder === undefined ? (
                        <Badge>{t('never')}</Badge>
                      ) : (
                        <span className="text-xs text-ink2">
                          {t('{{days}}d ago', { days: row.daysSinceLastOrder })}
                        </span>
                      )}
                    </Td>
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

function SortableTh({
  label,
  align = 'left',
  active,
  descending,
  onClick,
}: {
  label: string;
  align?: 'left' | 'right';
  active: boolean;
  descending: boolean;
  onClick: () => void;
}) {
  return (
    <Th align={align}>
      <button
        type="button"
        onClick={onClick}
        className="inline-flex items-center gap-1 uppercase hover:text-brand"
        aria-sort={active ? (descending ? 'descending' : 'ascending') : 'none'}
      >
        {label}
        <span aria-hidden className={active ? 'text-brand' : 'opacity-30'}>
          {active && !descending ? '▲' : '▼'}
        </span>
      </button>
    </Th>
  );
}

function compare(a: CustomerAnalyticsRow, b: CustomerAnalyticsRow, key: SortKey): number {
  switch (key) {
    case 'name':
      return (a.name ?? '').localeCompare(b.name ?? '');
    case 'city':
      return (a.city ?? '').localeCompare(b.city ?? '');
    case 'orderCount':
      return (a.orderCount ?? 0) - (b.orderCount ?? 0);
    case 'totalValue':
      return Number(a.totalValue ?? 0) - Number(b.totalValue ?? 0);
    case 'daysSinceLastOrder':
      // Never-ordered sorts as infinitely long ago, so it lands at one end rather than mixed in
      // among real dates where nobody can see it.
      return (
        (a.daysSinceLastOrder ?? Number.MAX_SAFE_INTEGER) -
        (b.daysSinceLastOrder ?? Number.MAX_SAFE_INTEGER)
      );
  }
}
