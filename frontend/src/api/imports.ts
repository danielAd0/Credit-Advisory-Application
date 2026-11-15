import { saveFile } from './download';
import { ApiError, api } from './http';
import * as mockImports from './mockImports';
import type { PageResponse } from './types';

export type ImportState = 'PREVIEWED' | 'COMMITTED' | 'CANCELLED' | 'EXPIRED';
export type ImportBlocker =
  | 'CONSULTANT_CONFLICTS'
  | 'CONSULTANTS_NOT_CONFIRMED'
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

export type ConsultantOutcome = 'EXISTING' | 'EMAIL_ADDED' | 'NEW' | 'CONFLICT';
export type ConsultantAttention = 'NO_EMAIL' | 'NOT_ACTIVE' | 'SIMILAR_NAME' | 'CONFLICT';

/** One consultant found in the workbook and what confirm will do with them. */
export interface ConsultantFound {
  outcome: ConsultantOutcome;
  matchedBy: 'EMAIL' | 'NAME' | null;
  namesInSheet: string[];
  email: string | null;
  rows: number;
  newClients: number;
  consultantId: number | null;
  consultantCode: string | null;
  consultantName: string | null;
  consultantStatus: string | null;
  attention: ConsultantAttention[];
  reason: string | null;
  similarConsultants: { consultantId: number; consultantCode: string; fullName: string }[];
}

export interface ConsultantList {
  items: ConsultantFound[];
  identified: number;
  existing: number;
  toRegister: number;
  conflicts: number;
  withoutEmail: number;
  /** Send back to confirm the list; null once the import is finished. */
  fingerprint: string | null;
  confirmed: boolean;
  confirmedAt: string | null;
  confirmedBy: string | null;
}

/** What a confirmed import did (kept with the import). */
export interface ImportResult {
  consultantsIdentified: number;
  consultantsRegistered: number;
  consultantsMatched: number;
  clientsLinked: number;
  transactionsLinked: number;
  transactionsUnattributed: number;
  rowsNeedingAttention: number;
}

export interface ConsultantRegistration {
  namesInSheet: number;
  matchedByName: number;
  matchedByEmail: number;

  toRegister: {
    fullName: string;
    email: string | null;
    otherSpellings: string[];
    rows: number;

    similarConsultants: { consultantId: number; consultantCode: string; fullName: string }[];
  }[];

  emailsToAdd: {
    consultantId: number;
    consultantCode: string;
    fullName: string;
    email: string;
    rows: number;
  }[];

  conflicts: { nameInSheet: string; email: string; reason: string; rows: number }[];
}

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
  consultants: ConsultantList;
  weekImportedByJobId: number | null;
  replaceRequired: boolean;
  canConfirm: boolean;
  blockers: ImportBlocker[];
  typedInSales: number;
  /** Rows whose ID number belongs to a client stored under another name (NAME_DIFFERENCES). */
  nameDifferences: number;
  possibleDuplicateRows: number;
  consultantRegistration?: ConsultantRegistration;
  assignment?: AssignmentPreview;
  result: ImportResult | null;
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
  replacedTypedInSales: number;
  consultantsRegistered?: number;
  consultantEmailsAdded?: number;
  consultantAliasesAdded?: number;
  newClientsUnassigned?: number;
  consultantsIdentified: number;
  consultantsMatched: number;
  clientsLinked: number;
  transactionsLinked: number;
  transactionsUnattributed: number;
  rejectedRows: number;
  possibleDuplicateRows: number;
  rowsNeedingAttention: number;
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

  async exportIssues(id: number, severity?: Severity): Promise<void> {
    const res = await fetch(`/api/imports/${id}/issues/export${query({ severity })}`, {
      credentials: 'include',
    });
    if (res.status === 401) {
      window.location.assign('/login');
      throw new ApiError(401, 'Please sign in again.');
    }
    if (!res.ok) throw new ApiError(res.status, 'Could not download the issues file.');
    const named = /filename="([^"]+)"/.exec(res.headers.get('Content-Disposition') ?? '');
    saveFile(await res.blob(), named?.[1] ?? `import-${id}-issues.csv`);
  },

  /** "Confirm Consultants and Proceed": the admin checked the consultant list. */
  confirmConsultants: (id: number, fingerprint: string) =>
    api<ImportSummary>(`/api/imports/${id}/consultants/confirm`, {
      method: 'POST',
      body: JSON.stringify({ fingerprint }),
    }),

  confirm: (
    id: number,
    fileDigest: string,
    replaceReason: string | null,
    acceptNameDifferences = false,
  ) =>
    api<ConfirmResult>(`/api/imports/${id}/confirm`, {
      method: 'POST',
      body: JSON.stringify({ fileDigest, replaceReason, acceptNameDifferences }),
    }),

  cancel: (id: number) => api<ImportSummary>(`/api/imports/${id}/cancel`, { method: 'POST' }),
};

export const imports = import.meta.env.VITE_USE_MOCK === 'true' ? mockImports : real;
