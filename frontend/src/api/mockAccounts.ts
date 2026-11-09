import { ApiError } from './http';
import { clients, consultants, delay } from './mock';
import type { Consultant, ConsultantAccount, UserAccount } from './accounts';
import type { ConsultantStatus, ConsultantSummary, PageResponse } from './types';

const now = () => new Date().toISOString();
const countFor = (id: number) => clients.filter((c) => c.consultant?.id === id).length;
const asConsultant = (c: ConsultantSummary): Consultant => ({
  ...c,
  clientCount: countFor(c.id),
});

function loginFor(c: ConsultantSummary, id: number): UserAccount {
  return {
    id,
    email: `${c.consultantCode.toLowerCase()}@example.com`,
    enabled: true,
    roles: ['CONSULTANT'],
    consultant: {
      id: c.id,
      consultantCode: c.consultantCode,
      fullName: c.fullName,
      status: c.status,
    },
    locked: c.id === 4,
    lockedUntil: c.id === 4 ? new Date(Date.now() + 15 * 60_000).toISOString() : null,
    mustChangePassword: false,
    lastLoginAt: c.id <= 6 ? now() : null,
    version: 0,
    createdAt: c.createdAt,
    updatedAt: c.updatedAt,
  };
}

const logins = new Map<number, UserAccount>(
  consultants.map((c, i) => [c.id, loginFor(c, i + 100)]),
);

const problem = (status: number, code: string, detail: string) =>
  new ApiError(status, detail, { type: `urn:credit-advisory:${code}`, detail });

export async function listConsultants(
  params: {
    search?: string;
    status?: ConsultantStatus;
    page?: number;
    size?: number;
  } = {},
): Promise<PageResponse<Consultant>> {
  await delay();
  const q = (params.search ?? '').trim().toLowerCase();
  const size = params.size ?? 25;
  const page = params.page ?? 0;
  const all = consultants
    .filter((c) => !params.status || c.status === params.status)
    .filter(
      (c) =>
        !q || c.fullName.toLowerCase().includes(q) || c.consultantCode.toLowerCase().includes(q),
    )
    .sort((a, b) => a.fullName.localeCompare(b.fullName))
    .map(asConsultant);
  return {
    items: all.slice(page * size, (page + 1) * size),
    page,
    size,
    totalItems: all.length,
    totalPages: Math.max(1, Math.ceil(all.length / size)),
  };
}

export async function createConsultantAccount(input: {
  consultantCode: string;
  fullName: string;
  email: string;
  temporaryPassword: string;
}): Promise<ConsultantAccount> {
  await delay();
  const code = input.consultantCode.trim().toUpperCase();
  const email = input.email.trim().toLowerCase();
  if (consultants.some((c) => c.consultantCode === code))
    throw problem(409, 'consultant:duplicate-code', 'That consultant code is already used.');
  if ([...logins.values()].some((u) => u.email === email))
    throw problem(409, 'user:duplicate-email', 'That email address is already used.');
  if (input.temporaryPassword.length < 12)
    throw problem(400, 'validation:invalid-request', 'The password needs at least 12 characters.');
  const id = Math.max(0, ...consultants.map((c) => c.id)) + 1;
  const c: ConsultantSummary = {
    id,
    consultantCode: code,
    fullName: input.fullName.trim(),
    status: 'ACTIVE',
    statusChangedAt: now(),
    version: 0,
    createdAt: now(),
    updatedAt: now(),
  };
  consultants.push(c);
  const user = {
    ...loginFor(c, id + 100),
    email,
    locked: false,
    lockedUntil: null,
  };
  user.mustChangePassword = true;
  user.lastLoginAt = null;
  logins.set(id, user);
  return { consultant: asConsultant(c), user };
}

export async function changeConsultantStatus(
  id: number,
  status: ConsultantStatus,
  reason: string,
  version: number,
): Promise<Consultant> {
  await delay();
  const c = consultants.find((x) => x.id === id);
  if (!c) throw problem(404, 'consultant:not-found', 'Consultant not found.');
  if (!reason.trim()) throw problem(400, 'validation:invalid-request', 'A reason is required.');
  if (c.version !== version)
    throw problem(409, 'conflict:stale-version', 'Someone else changed this consultant first.');
  c.status = status;
  c.statusChangedAt = now();
  c.updatedAt = now();
  c.version += 1;
  const u = logins.get(id);
  if (u?.consultant) u.consultant.status = status;
  return asConsultant(c);
}

export async function findLogin(consultantId: number): Promise<UserAccount | null> {
  await delay();
  const u = logins.get(consultantId);
  return u ? { ...u } : null;
}

export async function resetPassword(
  userId: number,
  temporaryPassword: string,
): Promise<UserAccount> {
  await delay();
  const u = [...logins.values()].find((x) => x.id === userId);
  if (!u) throw problem(404, 'user:not-found', 'User not found.');
  if (temporaryPassword.length < 12)
    throw problem(400, 'validation:weak-password', 'The password needs at least 12 characters.');
  u.mustChangePassword = true;
  u.locked = false;
  u.lockedUntil = null;
  u.version += 1;
  return { ...u };
}

export async function unlockUser(userId: number): Promise<UserAccount> {
  await delay();
  const u = [...logins.values()].find((x) => x.id === userId);
  if (!u) throw problem(404, 'user:not-found', 'User not found.');
  u.locked = false;
  u.lockedUntil = null;
  return { ...u };
}
