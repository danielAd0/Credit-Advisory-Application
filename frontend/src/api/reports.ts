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
  /** null for a consultant, or when no target is configured for that date. */
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

// ---------- any month or date range (docs/api-reports.md section 9) ----------

export interface MonthListItem {
  /** yyyy-mm */
  month: string;
  from: string;
  to: string;
  transactionCount: number;
  quotationTotal: number;
  bankedTotal: number;
}

export interface MonthsResponse {
  scope: ReportScope;
  months: MonthListItem[];
}

export interface RangeDay {
  date: string;
  dayOfWeek: number;
  transactionCount: number;
  clientCount: number;
  quotationTotal: number;
  bankedTotal: number;
  /** The daily target in force on that date; null for a consultant or when none is configured. */
  dailyTarget: number | null;
  targetMet: boolean | null;
}

/** The part of one Monday-to-Sunday week that falls inside the range. */
export interface RangeWeekPart {
  weekStarting: string;
  from: string;
  to: string;
  /** False when the range starts or ends mid-week. */
  wholeWeek: boolean;
  transactionCount: number;
  quotationTotal: number;
  bankedTotal: number;
  /** The weekly target in force that week; null for a consultant or when none is configured. */
  weeklyTarget: number | null;
  /** Only set for a whole week: a part-week is never judged against a full week's target. */
  targetMet: boolean | null;
}

export interface RangeSummaryResponse {
  scope: ReportScope;
  from: string;
  to: string;
  totals: {
    transactionCount: number;
    clientCount: number;
    unattributedCount: number | null;
    quotationTotal: number;
    bankedTotal: number;
    daysWithSales: number;
    /** Days with sales that had a target configured; 0 = "Target not configured". Null for a consultant. */
    daysJudged: number | null;
    /** Of the judged days, those that reached the target. Null for a consultant or when none was judged. */
    daysTargetMet: number | null;
  };
  days: RangeDay[];
  weeks: RangeWeekPart[];
}

export interface RangeConsultantsResponse {
  scope: ReportScope;
  from: string;
  to: string;
  consultants: {
    consultant: { id: number; consultantCode: string; fullName: string } | null;
    transactionCount: number;
    clientCount: number;
    quotationTotal: number;
    bankedTotal: number;
  }[];
}

export interface RangeProductsResponse {
  scope: ReportScope;
  from: string;
  to: string;
  consultantId: number | null;
  products: {
    product: { id: number; code: string; name: string };
    lineCount: number;
    totalAmount: number;
  }[];
}

export interface RangeBudgetResponse {
  from: string;
  to: string;
  structures: { budgetStructure: string; entryCount: number; loanAmountTotal: number }[];
}

const period = (from: string, to: string) => `from=${from}&to=${to}`;

const real = {
  weeks: () => api<WeeksResponse>('/api/reports/weeks'),
  summary: (week: string) => api<SummaryResponse>(`/api/reports/weeks/${week}/summary`),
  consultants: (week: string) => api<ConsultantsResponse>(`/api/reports/weeks/${week}/consultants`),
  products: (week: string, consultantId?: number) =>
    api<ProductsResponse>(
      `/api/reports/weeks/${week}/products${consultantId ? `?consultantId=${consultantId}` : ''}`,
    ),
  budget: (week: string) => api<BudgetResponse>(`/api/reports/weeks/${week}/budget-structures`),
  months: () => api<MonthsResponse>('/api/reports/months'),
  rangeSummary: (from: string, to: string) =>
    api<RangeSummaryResponse>(`/api/reports/range/summary?${period(from, to)}`),
  rangeConsultants: (from: string, to: string) =>
    api<RangeConsultantsResponse>(`/api/reports/range/consultants?${period(from, to)}`),
  rangeProducts: (from: string, to: string, consultantId?: number) =>
    api<RangeProductsResponse>(
      `/api/reports/range/products?${period(from, to)}${consultantId ? `&consultantId=${consultantId}` : ''}`,
    ),
  rangeBudget: (from: string, to: string) =>
    api<RangeBudgetResponse>(`/api/reports/range/budget-structures?${period(from, to)}`),
};

export const reports = import.meta.env.VITE_USE_MOCK === 'true' ? mockReports : real;
