import { ApiError, api } from './http';
import { delay } from './mock';
import { listConsultants } from './mockAccounts';
import type { ConsultantStatus, PageResponse } from './types';

export interface ConsultantAlias {
  id: number;
  alias: string;
  consultantId: number;
  consultantCode: string;
  consultantName: string;
  consultantStatus: ConsultantStatus;
  createdBy: string | null;
  createdAt: string;
  transactions: number;
}

export type AliasFilter = {
  consultantId?: number;
  search?: string;
  page?: number;
  size?: number;
};

function query(params: AliasFilter): string {
  const q = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== '') q.set(key, String(value));
  }
  const s = q.toString();
  return s ? `?${s}` : '';
}

const real = {
  list: (filter: AliasFilter = {}) =>
    api<PageResponse<ConsultantAlias>>(`/api/consultant-aliases${query(filter)}`),

  add: (consultantId: number, alias: string) =>
    api<ConsultantAlias>('/api/consultant-aliases', {
      method: 'POST',
      body: JSON.stringify({ consultantId, alias }),
    }),

  remove: (id: number) => api<undefined>(`/api/consultant-aliases/${id}`, { method: 'DELETE' }),
};

const problem = (status: number, code: string, detail: string) =>
  new ApiError(status, detail, { type: `urn:credit-advisory:${code}`, detail });

const tidy = (s: string) => s.trim().replace(/\s+/g, ' ');

const SEED: [string, number, number][] = [
  ['AMBITIOUS', 1, 41],
  ['A MOKOENA', 1, 6],
  ['BOITUMELO K', 2, 0],
  ['CENNET T', 3, 12],
];

let nextId = 100;
let aliases: ConsultantAlias[] | null = null;

async function seeded(): Promise<ConsultantAlias[]> {
  if (aliases) return aliases;
  const people = (await listConsultants({ size: 100 })).items;
  aliases = SEED.flatMap(([alias, index, transactions], n) => {
    const c = people[index % Math.max(1, people.length)];
    return c
      ? [
          {
            id: n + 1,
            alias,
            consultantId: c.id,
            consultantCode: c.consultantCode,
            consultantName: c.fullName,
            consultantStatus: c.status,
            createdBy: 'admin@example.com',
            createdAt: new Date(2026, 10, 9 + n).toISOString(),
            transactions,
          },
        ]
      : [];
  });
  return aliases;
}

const mock = {
  async list(filter: AliasFilter = {}): Promise<PageResponse<ConsultantAlias>> {
    await delay(300);
    const q = (filter.search ?? '').trim().toLowerCase();
    const all = (await seeded())
      .filter((a) => !filter.consultantId || a.consultantId === filter.consultantId)
      .filter(
        (a) =>
          !q || a.alias.toLowerCase().includes(q) || a.consultantName.toLowerCase().includes(q),
      )
      .sort(
        (a, b) =>
          a.consultantName.localeCompare(b.consultantName) || a.alias.localeCompare(b.alias),
      );
    const size = filter.size ?? 50;
    const page = filter.page ?? 0;
    return {
      items: all.slice(page * size, (page + 1) * size),
      page,
      size,
      totalItems: all.length,
      totalPages: Math.max(1, Math.ceil(all.length / size)),
    };
  },

  async add(consultantId: number, alias: string): Promise<ConsultantAlias> {
    await delay(300);
    const list = await seeded();
    const people = (await listConsultants({ size: 100 })).items;
    const c = people.find((p) => p.id === consultantId);
    if (!c) throw problem(404, 'consultant:not-found', 'Consultant not found.');
    const text = tidy(alias);
    if (!text || text.length > 200)
      throw problem(422, 'consultant-alias:invalid', 'Enter a spelling of up to 200 characters.');
    const key = text.toLowerCase();
    const taken =
      list.some((a) => a.alias.toLowerCase() === key) ||
      people.some((p) => p.fullName.toLowerCase() === key);
    if (taken)
      throw problem(
        409,
        'consultant-alias:taken',
        `"${text}" already matches a consultant, so it cannot be added again.`,
      );
    const created: ConsultantAlias = {
      id: nextId++,
      alias: text,
      consultantId: c.id,
      consultantCode: c.consultantCode,
      consultantName: c.fullName,
      consultantStatus: c.status,
      createdBy: 'admin@example.com',
      createdAt: new Date().toISOString(),
      transactions: 0,
    };
    list.push(created);
    return created;
  },

  async remove(id: number): Promise<undefined> {
    await delay(300);
    const list = await seeded();
    const at = list.findIndex((a) => a.id === id);
    if (at < 0) throw problem(404, 'consultant-alias:not-found', 'That spelling no longer exists.');
    list.splice(at, 1);
    return undefined;
  },
};

export const consultantAliases = import.meta.env.VITE_USE_MOCK === 'true' ? mock : real;
