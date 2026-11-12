import { api } from './http';
import { listEmployers, listProducts, type Employer, type Product } from './admin';
import type { ClientSummary, PageResponse } from './types';
import * as mockTransactions from './mockTransactions';

export interface Option {
  id: number;
  name: string;
}

export interface NewTransaction {
  clientId: number;
  consultantId?: number;
  transactionDate: string;
  lines: { productId: number; amount: number }[];
  amountToClient: number | null;
  thirdPartySettlement: number | null;
  paymentTermId: number | null;
  employerId: number | null;
  structureId: number | null;
  primaryStructureBudgetList: string | null;
  primaryStructureLoanAmount: number | null;
  secondaryStructureBudgetList: string | null;
  secondaryStructureLoanAmount: number | null;
}

export interface SavedLine {
  productId: number;
  name: string;
  amount: number;
  countsTowardQuotation: boolean;
  countsTowardBanked: boolean;
}

export interface SavedTransaction {
  id: number;
  clientId: number;
  consultantId: number;
  transactionDate: string;
  dayOfWeek: number;
  lines: SavedLine[];
  quotationTotal: number;
  bankedTotal: number;
  weekStarting?: string | null;
  replaced?: boolean;
  typedIn?: boolean;
}

export interface SaleDetail extends SavedTransaction {
  amountToClient: number | null;
  thirdPartySettlement: number | null;
  paymentTermId: number | null;
  employerId: number | null;
  structureId: number | null;
  primaryStructureBudgetList: string | null;
  primaryStructureLoanAmount: number | null;
  secondaryStructureBudgetList: string | null;
  secondaryStructureLoanAmount: number | null;
  editable: boolean;
  version: number;
}

export type EditSale = Omit<NewTransaction, 'clientId' | 'consultantId'> & { version: number };

export interface SaleRow extends SavedTransaction {
  clientName: string;
  consultantName: string;
}

export interface SaleFilter {
  from: string;
  to: string;
  consultantId?: number;
}

export interface SalesPage extends PageResponse<SaleRow> {
  quotationTotal: number;
  bankedTotal: number;
}

export interface CaptureLists {
  products: Product[];
  employers: Employer[];
  paymentTerms: Option[] | null;
  structures: Option[] | null;
}

async function everyPage<T>(fetch: (page: number) => Promise<PageResponse<T>>): Promise<T[]> {
  const first = await fetch(0);
  const rest = await Promise.all(
    Array.from({ length: Math.max(0, first.totalPages - 1) }, (_, i) => fetch(i + 1)),
  );
  return [first, ...rest].flatMap((p) => p.items);
}

const real = {
  async lists(): Promise<CaptureLists> {
    const [products, employers, terms, structures] = await Promise.allSettled([
      everyPage((page) => listProducts({ active: true, page, size: 100 })),
      everyPage((page) => listEmployers({ active: true, page, size: 100 })),
      api<Option[]>('/api/payment-terms'),
      api<Option[]>('/api/structures'),
    ]);

    if (products.status === 'rejected') throw products.reason;
    return {
      products: [...products.value].sort((a, b) => a.displayOrder - b.displayOrder),
      employers: employers.status === 'fulfilled' ? employers.value : [],
      paymentTerms: terms.status === 'fulfilled' ? terms.value : null,
      structures: structures.status === 'fulfilled' ? structures.value : null,
    };
  },

  lookupClient: (idNumber: string) =>
    api<ClientSummary>('/api/clients/lookup', {
      method: 'POST',
      body: JSON.stringify({ idNumber }),
    }),

  list(filter: SaleFilter, page: number, size = 25) {
    const q = new URLSearchParams({
      from: filter.from,
      to: filter.to,
      page: String(page),
      size: String(size),
    });
    if (filter.consultantId !== undefined) q.set('consultantId', String(filter.consultantId));
    return api<SalesPage>(`/api/transactions?${q.toString()}`);
  },

  forClient: (clientId: number) =>
    api<SavedTransaction[]>(`/api/transactions?clientId=${clientId}`),

  get: (id: number) => api<SaleDetail>(`/api/transactions/${id}`),

  update: (id: number, body: EditSale) =>
    api<SaleDetail>(`/api/transactions/${id}`, { method: 'PATCH', body: JSON.stringify(body) }),

  remove: (id: number, version: number) =>
    api<undefined>(`/api/transactions/${id}?version=${version}`, { method: 'DELETE' }),

  save: (t: NewTransaction, key: string) =>
    api<SavedTransaction>('/api/transactions', {
      method: 'POST',
      headers: { 'Idempotency-Key': key },
      body: JSON.stringify(t),
    }),
};

export const transactions = import.meta.env.VITE_USE_MOCK === 'true' ? mockTransactions : real;
