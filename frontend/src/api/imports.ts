import { api } from './http';
import * as mockImports from './mockImports';
import type { PageResponse } from './types';

export type ImportState = 'PREVIEWED' | 'COMMITTED' | 'CANCELLED' | 'EXPIRED';
export type ImportBlocker =
  | 'UNMAPPED_CONSULTANTS'
  | 'CONSULTANT_CONFLICTS'
  | 'NAME_DIFFERENCES'
  | 'SHEET_ERRORS'
  | 'EXPIRED'
  | 'NOT_PREVIEWED';
export type RowAction = 'NEW' | 'UNCHANGED' | 'DUPLICATE' | 'INVALID';
export type Severity = 'ERROR' | 'WARNING' | 'INFO';

export interface ImportDay {
  sheetName: string | null;
  dayOfWeek: number;
  date: string | null;
  rows: number;
  rejected: number;
  quotation: number;
  banked: number;
}

export interface UnmappedConsultant {
  nameInSheet: string;
  rows: number;
}

/** What confirm does with the CONSULTANT EMAIL column (docs/consultant-onboarding.md). */
export interface ConsultantRegistration {
  namesInSheet: number;
  matchedByName: number;
  matchedByEmail: number;
  /** New consultants confirm will create. */
  toRegister: {
    fullName: string;
    email: string;
    otherSpellings: string[];
    rows: number;
    /** Existing consultants with a similar name: maybe the same person under another email. */
    similarConsultants: { consultantId: number; consultantCode: string; fullName: string }[];
  }[];
  /** Existing consultants without an email who get the sheet's email. */
  emailsToAdd: {
    consultantId: number;
    consultantCode: string;
    fullName: string;
    email: string;
    rows: number;
  }[];
  /** Names that do not match their email safely. They stop the import. */
  conflicts: { nameInSheet: string; email: string; reason: string; rows: number }[];
}

/** New clients and who confirm will give them to. Existing clients keep their consultant. */
export interface AssignmentPreview {
  newClientRows: number;
  willBeAssigned: number;
  noConsultant: number;
  consultantNotActive: number;
  waitingForConsultant: number;
  existingClientsWithOtherConsultant: number;
}

export interface ImportSummary {
  id: number;
  fileName: string;
  fileDigest: string;
  state: ImportState;
  weekStarting: string;
  weekEnding: string;
  uploadedBy: string;
  createdAt: string;
  expiresAt: string | null;
  committedAt: string | null;
  totalRows: number;
  newClients: number;
  existingClients: number;
  repeatRows: number;
  rejectedRows: number;
  appliedRows: number;
  errors: number;
  warnings: number;
  infos: number;
  quotationTotal: number;
  bankedTotal: number;
  days: ImportDay[];
  unmappedConsultants: UnmappedConsultant[];
  weekImportedByJobId: number | null;
  replaceRequired: boolean;
  canConfirm: boolean;
  blockers: ImportBlocker[];
  /** Sales typed into the app for this week. Confirming replaces them: the spreadsheet wins. */
  typedInSales: number;
  /** Only while PREVIEWED (empty lists otherwise). Missing in older mock data. */
  consultantRegistration?: ConsultantRegistration;
  assignment?: AssignmentPreview;
}

export interface ImportHistoryItem {
  id: number;
  fileName: string;
  state: ImportState;
  weekStarting: string;
  uploadedBy: string;
  createdAt: string;
  committedAt: string | null;
  totalRows: number;
  rejectedRows: number;
  appliedRows: number;
  replacesImportJobId: number | null;
}

export interface ImportRow {
  id: number;
  sheetName: string;
  rowNumber: number;
  date: string;
  action: RowAction;
  outcome: string | null;
  idNumberMasked: string;
  firstName: string | null;
  lastName: string | null;
  consultantName: string | null;
  employerName: string | null;
  quotation: number;
  banked: number;
  products: Record<string, number>;
  warnings: number;
}

export interface ImportIssue {
  sheetName: string | null;
  rowNumber: number | null;
  field: string | null;
  code: string;
  severity: Severity;
  message: string;
}

export interface ConfirmResult {
  importJobId: number;
  reportingWeekId: number;
  weekStarting: string;
  transactions: number;
  productLines: number;
  extraValues: number;
  newClients: number;
  clientsAssigned: number;
  employersCreated: number;
  structuresCreated: number;
  paymentTermsCreated: number;
  staleRows: number;
  replacedImportJobId: number | null;
  supersededTransactions: number;
  /** Typed-in sales of this week that the import replaced. */
  replacedTypedInSales: number;
  /** Consultants registered from the sheet's emails (they still need a login: Consultant logins). */
  consultantsRegistered?: number;
  consultantEmailsAdded?: number;
  consultantAliasesAdded?: number;
  newClientsUnassigned?: number;
}

function query(params: Record<string, string | number | undefined>): string {
  const q = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== '') q.set(key, String(value));
  }
  const s = q.toString();
  return s ? `?${s}` : '';
}

const real = {
  weekGuess: (fileName: string) =>
    api<{ weekStarting: string | null }>(
      `/api/imports/weekly-register/week-guess${query({ fileName })}`,
    ).then((r) => r.weekStarting),

  preview: (file: File, weekStarting?: string) => {
    const body = new FormData();
    body.append('file', file);
    return api<ImportSummary>(`/api/imports/weekly-register/preview${query({ weekStarting })}`, {
      method: 'POST',
      body,
    });
  },

  list: (page = 0, size = 20) =>
    api<PageResponse<ImportHistoryItem>>(`/api/imports${query({ page, size })}`),

  get: (id: number) => api<ImportSummary>(`/api/imports/${id}`),

  rows: (
    id: number,
    params: { sheet?: string; action?: RowAction; page?: number; size?: number } = {},
  ) => api<PageResponse<ImportRow>>(`/api/imports/${id}/rows${query(params)}`),

  issues: (id: number, params: { severity?: Severity; page?: number; size?: number } = {}) =>
    api<PageResponse<ImportIssue>>(`/api/imports/${id}/issues${query(params)}`),

  mapConsultant: (id: number, nameInSheet: string, consultantId: number) =>
    api<ImportSummary>(`/api/imports/${id}/consultant-mappings`, {
      method: 'POST',
      body: JSON.stringify({ nameInSheet, consultantId }),
    }),

  confirm: (id: number, fileDigest: string, replaceReason: string | null) =>
    api<ConfirmResult>(`/api/imports/${id}/confirm`, {
      method: 'POST',
      body: JSON.stringify({ fileDigest, replaceReason }),
    }),

  cancel: (id: number) => api<ImportSummary>(`/api/imports/${id}/cancel`, { method: 'POST' }),
};

export const imports = import.meta.env.VITE_USE_MOCK === 'true' ? mockImports : real;
