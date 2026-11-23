import { saveFile } from './download';
import { ApiError, api } from './http';
import * as mockAudit from './mockAudit';
import type { PageResponse } from './types';

export interface AuditEntry {
  id: number;
  occurredAt: string;
  action: string;
  actor: { id: number; email: string } | null;
  targetType: string | null;
  targetId: string | null;
  metadata: Record<string, unknown> | null;
}

export interface AuditFilter {
  action?: string;
  targetType?: string;
  targetId?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

function query(params: AuditFilter): string {
  const q = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== '') q.set(key, String(value));
  }
  const s = q.toString();
  return s ? `?${s}` : '';
}

async function exportCsv(filter: AuditFilter = {}): Promise<void> {
  const res = await fetch(
    `/api/audit-logs/export${query({ ...filter, page: undefined, size: undefined })}`,
    { credentials: 'include', headers: { Accept: 'text/csv' } },
  );
  if (res.status === 401) {
    window.location.assign('/login');
    throw new ApiError(401, 'Please sign in again.');
  }
  if (!res.ok) {
    const problem = await res
      .json()
      .then((body: { type?: string; detail?: string }) => body)
      .catch(() => null);
    throw new ApiError(res.status, 'Could not download the audit log.', {
      type: problem?.type ?? null,
      detail: problem?.detail ?? null,
    });
  }
  const match = /filename="([^"]+)"/.exec(res.headers.get('Content-Disposition') ?? '');
  const today = new Date().toISOString().slice(0, 10);
  saveFile(await res.blob(), match ? match[1] : `audit-log-${today}.csv`);
}

const real = {
  list: (filter: AuditFilter = {}) =>
    api<PageResponse<AuditEntry>>(`/api/audit-logs${query(filter)}`),
  exportCsv,
};

export const audit = import.meta.env.VITE_USE_MOCK === 'true' ? mockAudit : real;
