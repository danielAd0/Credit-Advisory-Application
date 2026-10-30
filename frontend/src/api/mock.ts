import { ApiError }  from "./http";
import type { ClientSummary , ConsultantSummary , Role , Session } from "./types";

const delay = (ms = 250) => new Promise((r) => setTimeout(r , ms));

const consultants : ConsultantSummary[] = [

  { id: 'c1', fullName: 'Sample Consultant A', username: 'consultant.a', role: 'CONSULTANT', status: 'ACTIVE', clientCount: 4 },
  { id: 'c2', fullName: 'Sample Consultant B', username: 'consultant.b', role: 'CONSULTANT', status: 'ACTIVE', clientCount: 3 },
  { id: 'c3', fullName: 'Sample Consultant C', username: 'consultant.c', role: 'CONSULTANT', status: 'SUSPENDED', clientCount: 1 },
  { id: 'c4', fullName: 'Sample Manager', username: 'manager.a', role: 'MANAGER', status: 'ACTIVE', clientCount: 0 },
];

const clients: ClientSummary[] = Array.from({ length: 8 }, (_, i) => ({
  id: `k${i + 1}`,
  fullName: `Sample Client ${String(i + 1).padStart(2, '0')}`,
  idNumberMasked: `XXXXXXXXX${String(i + 1).padStart(4, '0')}`,
  consultantName: i === 7 ? null : consultants[i % 3].fullName,
  openCases: (i * 3) % 4,
  updatedAt: new Date(2026, 9, 20 - i).toISOString(),
}));

const KEY = 'mockerUser';

export async function getSession() : Promise<Session | null>{
    await delay(150);
    const username = sessionStorage.getItem(KEY);
    if(!username) return null;
    const role = (import.meta.env.VITE_MOCK_ROLE as Role | undefined) ?? 'MANAGER';
    return { username, role, mustChangePassword: false, csrf: { headerName: 'X-CSRF-TOKEN', token: 'mock' } };
}

export async function login(username: string, password: string, _remember: boolean): Promise<void> {
  await delay(600);
  if (!username || !password || password === 'wrong') throw new ApiError(401, 'Sign-in failed.');
  sessionStorage.setItem(KEY, username);
}

export async function listClients(query: string): Promise<ClientSummary[]> {
  await delay();
  const q = query.trim().toLowerCase();
  return q ? clients.filter((c) => c.fullName.toLowerCase().includes(q) || c.idNumberMasked.toLowerCase().includes(q)) : clients;
}

export async function listConsultants(): Promise<ConsultantSummary[]> {
  await delay();
  return consultants;
}

export async function logout() : Promise<void>{
  sessionStorage.removeItem(KEY);
}
