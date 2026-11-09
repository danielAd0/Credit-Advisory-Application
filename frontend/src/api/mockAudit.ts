import { ApiError } from './http';
import { delay } from './mock';
import type { AuditEntry, AuditFilter } from './audit';
import type { PageResponse } from './types';

const admin = { id: 1, email: 'admin@example.com' };
const manager = { id: 2, email: 'manager@example.com' };
const hour = 3600_000;
const ago = (h: number) => new Date(Date.now() - h * hour).toISOString();

const seed: Omit<AuditEntry, 'id'>[] = [
  {
    occurredAt: ago(1),
    action: 'CONSULTANT_STATUS_CHANGED',
    actor: admin,
    targetType: 'CONSULTANT',
    targetId: '4',
    metadata: { from: 'ACTIVE', to: 'SUSPENDED', reason: 'Under review', clientCount: 3 },
  },
  {
    occurredAt: ago(2),
    action: 'ACCOUNT_LOCKED',
    actor: null,
    targetType: 'USER',
    targetId: '104',
    metadata: { lockedUntil: ago(1.75) },
  },
  {
    occurredAt: ago(3),
    action: 'USER_PASSWORD_RESET',
    actor: admin,
    targetType: 'USER',
    targetId: '104',
    metadata: null,
  },
  {
    occurredAt: ago(5),
    action: 'CLIENTS_TRANSFERRED',
    actor: manager,
    targetType: 'CONSULTANT',
    targetId: '2',
    metadata: { fromConsultantId: 1, count: 2, reason: 'Workload', batchId: 'batch-1' },
  },
  {
    occurredAt: ago(8),
    action: 'CONSULTANT_ACCOUNT_CREATED',
    actor: admin,
    targetType: 'CONSULTANT',
    targetId: '12',
    metadata: { consultantCode: 'C012', userId: 112, email: 'c012@example.com' },
  },
  {
    occurredAt: ago(9),
    action: 'CLIENT_CREATED',
    actor: manager,
    targetType: 'CLIENT',
    targetId: '9',
    metadata: { consultantId: 3 },
  },
  {
    occurredAt: ago(12),
    action: 'CLIENT_UPDATED',
    actor: manager,
    targetType: 'CLIENT',
    targetId: '9',
    metadata: { fields: ['phone', 'email'] },
  },
  {
    occurredAt: ago(20),
    action: 'SIGN_IN_RATE_LIMITED',
    actor: null,
    targetType: null,
    targetId: null,
    metadata: { address: '203.0.113.9', failures: 20, window: 'PT5M' },
  },
  {
    occurredAt: ago(26),
    action: 'CASE_UPDATED',
    actor: manager,
    targetType: 'CASE',
    targetId: '1',
    metadata: { fields: ['status'], fromStatus: 'OPEN', toStatus: 'IN_PROGRESS' },
  },
  {
    occurredAt: ago(30),
    action: 'USER_ROLES_CHANGED',
    actor: admin,
    targetType: 'USER',
    targetId: '103',
    metadata: { from: ['CONSULTANT'], to: ['MANAGER'] },
  },
  {
    occurredAt: ago(48),
    action: 'CONSULTANT_CREATED',
    actor: admin,
    targetType: 'CONSULTANT',
    targetId: '11',
    metadata: { consultantCode: 'C011', fullName: 'Cennet Tshisimba' },
  },
];

const entries: AuditEntry[] = Array.from({ length: 40 }, (_, i) => ({
  ...seed[i % seed.length],
  occurredAt: ago(1 + i * 2.5),
})).map((e, i) => ({ ...e, id: 900 - i }));

export async function list(filter: AuditFilter = {}): Promise<PageResponse<AuditEntry>> {
  await delay(300);
  const from = filter.from ? Date.parse(filter.from) : null;
  const to = filter.to ? Date.parse(filter.to) : null;
  if (from !== null && to !== null && from >= to)
    throw new ApiError(400, 'The start must be before the end.', {
      type: 'urn:credit-advisory:validation:invalid-filter',
      detail: 'The start date must be before the end date.',
    });
  const all = entries.filter((e) => {
    const t = Date.parse(e.occurredAt);
    return (
      (!filter.action || e.action === filter.action.toUpperCase()) &&
      (!filter.targetType || e.targetType === filter.targetType.toUpperCase()) &&
      (!filter.targetId || e.targetId === filter.targetId) &&
      (from === null || t >= from) &&
      (to === null || t < to)
    );
  });
  const size = filter.size ?? 50;
  const page = filter.page ?? 0;
  return {
    items: all.slice(page * size, (page + 1) * size),
    page,
    size,
    totalItems: all.length,
    totalPages: Math.max(1, Math.ceil(all.length / size)),
  };
}
