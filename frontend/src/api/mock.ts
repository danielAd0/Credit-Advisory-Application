import { ApiError } from './http';
import type { ClientSummary, ConsultantSummary, Role, Session } from './types';

// Invented data only - never real client details (contract: mock data must be invented).

const delay = (ms = 250) => new Promise((r) => setTimeout(r, ms));
const when = (day: number) => new Date(2026, 9, day).toISOString();

const consultants: ConsultantSummary[] = [
  {
    id: 1,
    consultantCode: 'C001',
    fullName: 'Sample Consultant A',
    status: 'ACTIVE',
    statusChangedAt: when(1),
    version: 0,
    createdAt: when(1),
    updatedAt: when(1),
  },
  {
    id: 2,
    consultantCode: 'C002',
    fullName: 'Sample Consultant B',
    status: 'ACTIVE',
    statusChangedAt: when(2),
    version: 0,
    createdAt: when(2),
    updatedAt: when(2),
  },
  {
    id: 3,
    consultantCode: 'C003',
    fullName: 'Sample Consultant C',
    status: 'SUSPENDED',
    statusChangedAt: when(15),
    version: 1,
    createdAt: when(3),
    updatedAt: when(15),
  },
];

const clients: ClientSummary[] = Array.from({ length: 8 }, (_, i) => {
  const c = i === 7 ? null : consultants[i % 3];
  return {
    id: i + 1,
    idNumberMasked: `*********${String(i + 1).padStart(4, '0')}`,
    firstName: 'Sample',
    lastName: `Client ${String(i + 1).padStart(2, '0')}`,
    email: null,
    phone: null,
    consultant: c && {
      id: c.id,
      consultantCode: c.consultantCode,
      fullName: c.fullName,
      status: c.status,
    },
    version: 0,
    createdAt: when(20 - i),
    updatedAt: when(20 - i),
  };
});

const KEY = 'mockUser';

function mockSession(email: string): Session {
  const role = (import.meta.env.VITE_MOCK_ROLE as Role | undefined) ?? 'MANAGER';
  const consultant =
    role === 'CONSULTANT'
      ? { id: 1, consultantCode: 'C001', fullName: 'Sample Consultant A' }
      : null;
  return { id: 1, email, roles: [role], consultant };
}

export async function getSession(): Promise<Session | null> {
  await delay(150);
  const email = sessionStorage.getItem(KEY);
  return email ? mockSession(email) : null;
}

export async function login(email: string, password: string): Promise<Session> {
  await delay(600);
  if (!email || !password || password === 'wrong') throw new ApiError(401, 'Sign-in failed.');
  sessionStorage.setItem(KEY, email);
  return mockSession(email);
}

export async function listClients(search: string): Promise<ClientSummary[]> {
  await delay();
  const q = search.trim().toLowerCase();
  return q
    ? clients.filter((c) => `${c.firstName} ${c.lastName}`.toLowerCase().includes(q))
    : clients;
}

export async function listConsultants(): Promise<ConsultantSummary[]> {
  await delay();
  return consultants;
}

export async function logout(): Promise<void> {
  sessionStorage.removeItem(KEY);
}
