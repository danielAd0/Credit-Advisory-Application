import { api } from './http';
import * as mockReports from './mockReports';

export type ReportScope = 'ALL' | 'OWN';

export interface WeekListItem {
  weekStarting: string;
  transactionCount: number;
  quotationTotal: number;
  bankedTotal: number;
}

export interface WeeksResponse {
  scope: ReportScope;
  weeks: WeekListItem[];
}

export interface SummaryDay {
  dayOfWeek: number;
  date: string;
  transactionCount: number;
  clientCount: number;
  quotationTotal: number;
  bankedTotal: number;
  /** null for a consultant. */
  dailyTarget: number | null;
  targetMet: boolean | null;
}

export interface SummaryResponse {
  scope: ReportScope;
  weekStarting: string;
  weekEnding: string;
  days: SummaryDay[];
  week: {
    transactionCount: number;
    clientCount: number;
    unattributedCount: number | null;
    quotationTotal: number;
    bankedTotal: number;
    weeklyTarget: number | null;
    targetMet: boolean | null;
  };
}

export interface ConsultantDay {
  dayOfWeek: number;
  transactionCount: number;
  quotationTotal: number;
  bankedTotal: number;
}

export interface ConsultantReportRow {
  consultant: { id: number; consultantCode: string; fullName: string } | null;
  days: ConsultantDay[];
  week: {
    transactionCount: number;
    clientCount: number;
    quotationTotal: number;
    bankedTotal: number;
  };
}

export interface ConsultantsResponse {
  scope: ReportScope;
  weekStarting: string;
  consultants: ConsultantReportRow[];
}

export interface ProductDay {
  dayOfWeek: number;
  lineCount: number;
  totalAmount: number;
}

export interface ProductReportRow {
  product: { id: number; code: string; name: string };
  days: ProductDay[];
  week: { lineCount: number; totalAmount: number };
}

export interface ProductsResponse {
  scope: ReportScope;
  weekStarting: string;
  consultantId: number | null;
  products: ProductReportRow[];
}

export interface BudgetDay {
  dayOfWeek: number;
  entryCount: number;
  loanAmountTotal: number;
}

export interface BudgetStructureRow {
  budgetStructure: string;
  days: BudgetDay[];
  week: { entryCount: number; loanAmountTotal: number };
}

export interface BudgetResponse {
  weekStarting: string;
  structures: BudgetStructureRow[];
}

const real = {
  weeks: () => api<WeeksResponse>('/api/reports/weeks'),
  summary: (week: string) => api<SummaryResponse>(`/api/reports/weeks/${week}/summary`),
  consultants: (week: string) => api<ConsultantsResponse>(`/api/reports/weeks/${week}/consultants`),
  products: (week: string, consultantId?: number) =>
    api<ProductsResponse>(
      `/api/reports/weeks/${week}/products${consultantId ? `?consultantId=${consultantId}` : ''}`,
    ),
  budget: (week: string) => api<BudgetResponse>(`/api/reports/weeks/${week}/budget-structures`),
};

export const reports = import.meta.env.VITE_USE_MOCK === 'true' ? mockReports : real;
