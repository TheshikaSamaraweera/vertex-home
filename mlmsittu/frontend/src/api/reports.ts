import { useQuery } from '@tanstack/react-query';
import { api, type PagedResponse } from './client';
import type { components } from './schema';

type Schemas = components['schemas'];

export type StockReport = Schemas['StockReport'];
export type StockReportRow = Schemas['StockReportRow'];
export type SalesReport = Schemas['SalesReport'];
export type DailySales = Schemas['DailySales'];
export type ProductSales = Schemas['ProductSales'];
export type CustomerAnalyticsRow = Schemas['CustomerAnalyticsRow'];
export type TreemapNode = Schemas['TreemapNode'];

export const reportKeys = {
  stock: ['reports', 'stock'] as const,
  sales: ['reports', 'sales'] as const,
  customers: ['reports', 'customers'] as const,
  treemap: ['reports', 'treemap'] as const,
};

export type StockFilters = {
  locationId?: string;
  categoryId?: string;
  belowReorderOnly?: boolean;
  includeInactive?: boolean;
};

export const useStockReport = (filters: StockFilters) =>
  useQuery({
    queryKey: [...reportKeys.stock, filters],
    queryFn: () => api.get<StockReport>('/api/v1/reports/stock', { ...filters }),
  });

export type DateRange = { from?: string; to?: string };

export const useSalesReport = (range: DateRange) =>
  useQuery({
    queryKey: [...reportKeys.sales, range],
    queryFn: () => api.get<SalesReport>('/api/v1/reports/sales', { ...range }),
  });

export const useCustomerAnalytics = (includeInactive = false) =>
  useQuery({
    queryKey: [...reportKeys.customers, includeInactive],
    queryFn: () =>
      api
        .get<PagedResponse<CustomerAnalyticsRow>>('/api/v1/reports/customers', { includeInactive })
        .then((page) => page.data),
  });

export const useCustomerTreemap = (range: DateRange) =>
  useQuery({
    queryKey: [...reportKeys.treemap, range],
    queryFn: () => api.get<TreemapNode>('/api/v1/reports/customers/treemap', { ...range }),
  });

/**
 * Downloads a CSV.
 *
 * A plain link would work, but the export endpoints are cookie-authenticated and a link with query
 * parameters ends up in browser history — which for a report URL is a list of who looked at what
 * commercial data, sitting in plain text on a shared machine. Fetching and handing the browser a
 * blob keeps the URL out of history and lets an error surface as an error instead of a downloaded
 * file full of JSON.
 */
export async function downloadCsv(
  path: string,
  query: Record<string, string | number | boolean | undefined>,
  filename: string,
) {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== '') params.set(key, String(value));
  }
  const url = params.toString() ? `${path}?${params}` : path;

  const response = await fetch(url, { credentials: 'include' });
  if (!response.ok) {
    const problem = await response.json().catch(() => undefined);
    throw new Error(problem?.detail ?? 'The export could not be produced.');
  }

  const blob = await response.blob();
  const objectUrl = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = objectUrl;
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(objectUrl);
}
