import { saveFile } from './download';
import { ApiError, api } from './http';
import * as mockClientList from './mockClientListImport';
import type { PageResponse } from './types';

export type ClientListState = 'PREVIEWED' | 'COMMITTED' | 'CANCELLED' | 'EXPIRED';
export type ClientListBlocker =
  'FILE_ERRORS' | 'CONSULTANT_CONFLICTS' | 'NOTHING_TO_IMPORT' | 'EXPIRED' | 'FINISHED';
export type ClientListAction =
  'NEW' | 'UPDATE' | 'UNCHANGED' | 'CONFLICT' | 'DUPLICATE' | 'INVALID';
export type ClientListSeverity = 'ERROR' | 'WARNING' | 'INFO';

export interface ClientListColumn {
  header: string;
  field: string | null;
}

export interface ClientListMatched {
  consultantId: number;
  consultantCode: string;
  fullName: string;
  status: string;
  namesInSheet: string[];
  rows: number;
}

export interface ClientListToRegister {
  fullName: string;
  email: string | null;
  otherSpellings: string[];
  rows: number;
}

export interface ClientListEmailToAdd {
  consultantId: number;
  consultantCode: string;
  fullName: string;
  email: string;
}

export interface ClientListConflict {
  nameInSheet: string;
  email: string | null;
  reason: string;
  rows: number;
}

export interface ClientListSummary {
  id: number;
  state: ClientListState;
  fileName: string;
  fileDigest: string;
  sheetName: string | null;
  uploadedBy: string;
  createdAt: string;
  expiresAt: string;
  committedAt: string | null;
  rows: {
    total: number;
    newClients: number;
    update: number;
    unchanged: number;
    conflict: number;
    duplicate: number;
    invalid: number;
  };
  issues: { errors: number; warnings: number; info: number };
  columns: ClientListColumn[];
  consultants: {
    fingerprint: string;
    matched: ClientListMatched[];
    toRegister: ClientListToRegister[];
    emailsToAdd: ClientListEmailToAdd[];
    conflicts: ClientListConflict[];
  };
  additions: {
    debitOrders: number;
    withCellphone: number;
    withWorkPhone: number;
    withHomePhone: number;
    withEmail: number;
    withFileNumber: number;
    withFileId: number;
    withEmployer: number;
    withProductQuotation: number;
    withoutConsultant: number;
  };
  newEmployers: string[];
  newProductQuotations: string[];
  blockers: ClientListBlocker[];
  canConfirm: boolean;
  result: {
    newClients: number;
    appliedRows: number;
    staleRows: number;
    consultantsRegistered: number;
    clientsLinked: number;
  } | null;
}

export interface ClientListHistoryItem {
  id: number;
  state: ClientListState;
  fileName: string;
  sheetName: string | null;
  uploadedBy: string;
  totalRows: number;
  newClients: number;
  createdAt: string;
  committedAt: string | null;
}

export interface ClientListRow {
  rowNumber: number;
  action: ClientListAction;
  firstName: string | null;
  lastName: string | null;
  idNumber: string | null;
  consultantName: string | null;
  createsClient: boolean;
  assignsConsultant: boolean;
  warningCount: number;
  outcome: string | null;
}

export interface ClientListIssue {
  rowNumber: number | null;
  field: string | null;
  code: string;
  severity: ClientListSeverity;
  message: string;
}

export interface ClientListConfirmResult {
  importJobId: number;
  newClients: number;
  updatedClients: number;
  unchangedClients: number;
  assigned: number;
  newClientsWithoutConsultant: number;
  consultantsRegistered: number;
  consultantEmailsAdded: number;
  consultantAliasesAdded: number;
  employersCreated: number;
  productQuotationsCreated: number;
  staleRows: number;
  skippedRows: number;
}

function query(params: Record<string, string | number | undefined>): string {
  const q = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== '') q.set(key, String(value));
  }
  const s = q.toString();
  return s ? `?${s}` : '';
}

const base = '/api/imports/client-list';

const real = {
  preview: (file: File) => {
    const body = new FormData();
    body.append('file', file);
    return api<ClientListSummary>(`${base}/preview`, { method: 'POST', body });
  },

  list: (page = 0, size = 10) =>
    api<PageResponse<ClientListHistoryItem>>(`${base}${query({ page, size })}`),

  get: (id: number) => api<ClientListSummary>(`${base}/${id}`),

  rows: (id: number, params: { action?: ClientListAction; page?: number; size?: number } = {}) =>
    api<PageResponse<ClientListRow>>(`${base}/${id}/rows${query(params)}`),

  issues: (
    id: number,
    params: { severity?: ClientListSeverity; page?: number; size?: number } = {},
  ) => api<PageResponse<ClientListIssue>>(`${base}/${id}/issues${query(params)}`),

  async exportIssues(id: number, severity?: ClientListSeverity): Promise<void> {
    const res = await fetch(`${base}/${id}/issues/export${query({ severity })}`, {
      credentials: 'include',
    });
    if (res.status === 401) {
      window.location.assign('/login');
      throw new ApiError(401, 'Please sign in again.');
    }
    if (!res.ok) throw new ApiError(res.status, 'Could not download the issues file.');
    const named = /filename="([^"]+)"/.exec(res.headers.get('Content-Disposition') ?? '');
    saveFile(await res.blob(), named?.[1] ?? `client-list-${id}-issues.csv`);
  },

  confirm: (id: number, fileDigest: string, consultantsFingerprint: string) =>
    api<ClientListConfirmResult>(`${base}/${id}/confirm`, {
      method: 'POST',
      body: JSON.stringify({ fileDigest, consultantsFingerprint }),
    }),

  cancel: (id: number) => api<ClientListSummary>(`${base}/${id}/cancel`, { method: 'POST' }),
};

export const clientListImports = import.meta.env.VITE_USE_MOCK === 'true' ? mockClientList : real;
