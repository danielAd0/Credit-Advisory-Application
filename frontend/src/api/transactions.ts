import { api } from './http';
import { listEmployers, listProducts, type Employer, type Product } from './admin';
import type { ClientSummary } from './types';
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
}

export interface CaptureLists {
  products: Product[];
  employers: Employer[];
  paymentTerms: Option[] | null;
  structures: Option[] | null;
}

const real = {
  async lists(): Promise<CaptureLists> {
    const [products, employers, terms, structures] = await Promise.allSettled([
      listProducts({ active: true, size: 200 }),
      listEmployers({ active: true, size: 500 }),
      api<Option[]>('/api/payment-terms'),
      api<Option[]>('/api/structures'),
    ]);

    if (products.status === 'rejected') throw products.reason;
    return {
      products: [...products.value.items].sort((a, b) => a.displayOrder - b.displayOrder),
      employers: employers.status === 'fulfilled' ? employers.value.items : [],
      paymentTerms: terms.status === 'fulfilled' ? terms.value : null,
      structures: structures.status === 'fulfilled' ? structures.value : null,
    };
  },

  lookupClient: (idNumber: string) =>
    api<ClientSummary>('/api/clients/lookup', {
      method: 'POST',
      body: JSON.stringify({ idNumber }),
    }),

  save: (t: NewTransaction, key: string) =>
    api<SavedTransaction>('/api/transactions', {
      method: 'POST',
      headers: { 'Idempotency-Key': key },
      body: JSON.stringify(t),
    }),
};

export const transactions = import.meta.env.VITE_USE_MOCK === 'true' ? mockTransactions : real;
