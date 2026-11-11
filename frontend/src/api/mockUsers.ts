import { ApiError } from './http';
import { delay } from './mock';
import type { UserAccount } from './admin';
import type { PageResponse, Role } from './types';

const now = () => new Date().toISOString();

function staff(id: number, email: string, roles: Role[], extra: Partial<UserAccount> = {}) {
  return {
    id,
    email,
    enabled: true,
    roles,
    consultant: null,
    locked: false,
    lockedUntil: null,
    lastLoginAt: now(),
    mustChangePassword: false,
    version: 0,
    createdAt: now(),
    updatedAt: now(),
    ...extra,
  } satisfies UserAccount;
}

const people: UserAccount[] = [
  staff(1, 'admin@example.com', ['ADMIN']),
  staff(2, 'lerato.manager@example.com', ['MANAGER']),
  staff(3, 'sipho.dlamini@example.com', ['ADMIN', 'MANAGER']),
  staff(4, 'old.manager@example.com', ['MANAGER'], { enabled: false, lastLoginAt: null }),
  staff(5, 'locked.manager@example.com', ['MANAGER'], {
    locked: true,
    lockedUntil: new Date(Date.now() + 15 * 60_000).toISOString(),
  }),
];

const problem = (status: number, code: string, detail: string) =>
  new ApiError(status, detail, { type: `urn:credit-advisory:${code}`, detail });

function find(id: number): UserAccount {
  const u = people.find((x) => x.id === id);
  if (!u) throw problem(404, 'user:not-found', 'User not found.');
  return u;
}

function requireVersion(u: UserAccount, version: number) {
  if (u.version !== version)
    throw problem(409, 'conflict:stale-version', 'Someone else changed this user first.');
}

function requireAnotherAdmin() {
  if (people.filter((u) => u.enabled && u.roles.includes('ADMIN')).length <= 1)
    throw problem(
      409,
      'user:last-admin',
      'This is the only active administrator. Give another user the ADMIN role first.',
    );
}

function touch(u: UserAccount): UserAccount {
  u.version += 1;
  u.updatedAt = now();
  return { ...u, roles: [...u.roles] };
}

export async function listUsers(
  params: { search?: string; role?: Role; page?: number; size?: number } = {},
): Promise<PageResponse<UserAccount>> {
  await delay();
  const q = (params.search ?? '').trim().toLowerCase();
  const size = params.size ?? 20;
  const page = params.page ?? 0;
  const all = people
    .filter((u) => !params.role || u.roles.includes(params.role))
    .filter((u) => !q || u.email.includes(q))
    .sort((a, b) => a.email.localeCompare(b.email));
  return {
    items: all.slice(page * size, (page + 1) * size).map((u) => ({ ...u, roles: [...u.roles] })),
    page,
    size,
    totalItems: all.length,
    totalPages: Math.max(1, Math.ceil(all.length / size)),
  };
}

export async function createUser(input: {
  email: string;
  temporaryPassword: string;
  roles: Role[];
  consultantId?: number | null;
}): Promise<UserAccount> {
  await delay();
  const email = input.email.trim().toLowerCase();
  if (people.some((u) => u.email === email))
    throw problem(409, 'user:duplicate-email', 'A user with this email already exists.');
  if (input.temporaryPassword.length < 12)
    throw problem(400, 'validation:weak-password', 'The password needs at least 12 characters.');
  const u = staff(Math.max(0, ...people.map((x) => x.id)) + 1, email, [...input.roles].sort(), {
    mustChangePassword: true,
    lastLoginAt: null,
  });
  people.push(u);
  return { ...u };
}

export async function changeRoles(id: number, roles: Role[], version: number) {
  await delay();
  const u = find(id);
  requireVersion(u, version);
  if (u.enabled && u.roles.includes('ADMIN') && !roles.includes('ADMIN')) requireAnotherAdmin();
  u.roles = [...roles].sort();
  return touch(u);
}

export async function setEnabled(id: number, enabled: boolean, version: number) {
  await delay();
  const u = find(id);
  requireVersion(u, version);
  if (!enabled && u.enabled && u.roles.includes('ADMIN')) requireAnotherAdmin();
  u.enabled = enabled;
  return touch(u);
}

export async function resetPassword(id: number, temporaryPassword: string) {
  await delay();
  const u = find(id);
  if (temporaryPassword.length < 12)
    throw problem(400, 'validation:weak-password', 'The password needs at least 12 characters.');
  u.mustChangePassword = true;
  u.locked = false;
  u.lockedUntil = null;
  return touch(u);
}

export async function unlock(id: number) {
  await delay();
  const u = find(id);
  u.locked = false;
  u.lockedUntil = null;
  return touch(u);
}
