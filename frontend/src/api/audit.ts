import { api } from './http';
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

const real = {
  list: (filter: AuditFilter = {}) =>
    api<PageResponse<AuditEntry>>(`/api/audit-logs${query(filter)}`),
};

export const audit = import.meta.env.VITE_USE_MOCK === 'true' ? mockAudit : real;
