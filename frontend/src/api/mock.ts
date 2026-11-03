import { ApiError } from './http';
import type {
  AssignmentEntry,
  ClientDetail,
  ClientFilter,
  ClientSummary,
  ClientUpdate,
  ConsultantRef,
  ConsultantSummary,
  ImportPreview,
  ImportResult,
  MoveRequest,
  MoveResult,
  NewClient,
  Role,
  Session,
} from './types';

// Consultant names are the real staff list. Client details are invented, never real
// (contract: mock data must be invented).

const delay = (ms = 250) => new Promise((r) => setTimeout(r, ms));
const when = (day: number) => new Date(2026, 9, day).toISOString();

function consultant(id: number, fullName: string): ConsultantSummary {
  return {
    id,
    consultantCode: `C${String(id).padStart(3, '0')}`,
    fullName,
    status: 'ACTIVE',
    statusChangedAt: when(1),
    version: 0,
    createdAt: when(1),
    updatedAt: when(1),
  };
}

const consultants: ConsultantSummary[] = [
  consultant(1, 'Boitumelo Khumalo'),
  consultant(2, 'Thandi Xundu'),
  consultant(3, 'Fhatu Mavuna'),
  consultant(4, 'Sanele Dlamini'),
  consultant(5, 'Constance Baloyi'),
  consultant(6, 'Nosisa Dlamini'),
  consultant(7, 'Dipuo Keister'),
  consultant(8, 'Abigail'),
  consultant(9, 'Ambitious'),
  consultant(10, 'Victoria'),
  consultant(11, 'Cennet Tshisimba'),
];

const ref = (c: ConsultantSummary): ConsultantRef => ({
  id: c.id,
  consultantCode: c.consultantCode,
  fullName: c.fullName,
});

const link = (c: ConsultantSummary) => ({
  id: c.id,
  consultantCode: c.consultantCode,
  fullName: c.fullName,
  status: c.status,
});

const clients: ClientSummary[] = Array.from({ length: 8 }, (_, i) => {
  const c = i === 7 ? null : consultants[i % 3];
  return {
    id: i + 1,
    idNumberMasked: `*********${String(i + 1).padStart(4, '0')}`,
    firstName: 'Sample',
    lastName: `Client ${String(i + 1).padStart(2, '0')}`,
    email: `client${i + 1}@example.com`,
    phone: `+2782000000${i + 1}`,
    consultant: c && link(c),
    version: 0,
    createdAt: when(20 - i),
    updatedAt: when(20 - i),
  };
});

let nextId = clients.length + 1;

/** Newest first, like the real endpoint. */
const assignments = new Map<number, AssignmentEntry[]>();

const KEY = 'mockUser';
const who = () => sessionStorage.getItem(KEY) ?? 'unknown';

function mockSession(email: string): Session {
  const role = (import.meta.env.VITE_MOCK_ROLE as Role | undefined) ?? 'MANAGER';
  const mine = role === 'CONSULTANT' ? ref(consultants[0]) : null;
  return { id: 1, email, roles: [role], consultant: mine };
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

export async function logout(): Promise<void> {
  sessionStorage.removeItem(KEY);
}

export async function listClientsBy(filter: ClientFilter): Promise<ClientSummary[]> {
  await delay();
  const q = (filter.search ?? '').trim().toLowerCase();
  return clients.filter((c) => {
    if (q && !`${c.firstName} ${c.lastName}`.toLowerCase().includes(q)) return false;
    if (filter.unassigned && c.consultant) return false;
    if (filter.consultantId !== undefined && c.consultant?.id !== filter.consultantId) return false;
    return true;
  });
}

export const listClients = (search: string) => listClientsBy({ search });

export async function listConsultants(): Promise<ConsultantSummary[]> {
  await delay();
  return consultants;
}

export async function getClient(id: number): Promise<ClientDetail> {
  await delay(300);
  const c = clients.find((k) => k.id === id);
  if (!c) throw new ApiError(404, 'Client not found.');
  return { ...c };
}

export async function createClient(c: NewClient): Promise<ClientSummary> {
  await delay(500);
  const masked = `*********${c.idNumber.slice(-4)}`;
  if (clients.some((k) => k.idNumberMasked === masked)) throw new ApiError(409, 'Duplicate');
  const target = consultants.find((x) => x.id === c.consultantId);
  const now = new Date().toISOString();
  const created: ClientSummary = {
    id: nextId++,
    idNumberMasked: masked,
    firstName: c.firstName.trim(),
    lastName: c.lastName.trim(),
    email: c.email.trim() || null,
    phone: c.phone.trim() || null,
    consultant: target ? link(target) : null,
    version: 0,
    createdAt: now,
    updatedAt: now,
  };
  clients.unshift(created);
  if (target) {
    assignments.set(created.id, [
      {
        changedAt: now,
        changeType: 'MANUAL',
        fromConsultant: null,
        toConsultant: ref(target),
        reason: 'Client created',
        changedBy: { id: 1, email: who() },
        batchId: null,
      },
    ]);
  }
  return { ...created };
}

export async function updateClient(id: number, u: ClientUpdate): Promise<ClientSummary> {
  await delay(500);
  const c = clients.find((k) => k.id === id);
  if (!c) throw new ApiError(404, 'Client not found.');
  if (u.version !== c.version) throw new ApiError(409, 'Stale version.');
  if (u.idNumber) {
    const masked = `*********${u.idNumber.slice(-4)}`;
    if (clients.some((k) => k.id !== id && k.idNumberMasked === masked)) {
      throw new ApiError(409, 'Duplicate');
    }
    c.idNumberMasked = masked;
  }
  c.firstName = u.firstName.trim();
  c.lastName = u.lastName.trim();
  c.email = u.email.trim() || null;
  c.phone = u.phone.trim() || null;
  c.version += 1;
  c.updatedAt = new Date().toISOString();
  return { ...c };
}

export async function listAssignments(id: number): Promise<AssignmentEntry[]> {
  await delay(300);
  if (!clients.some((k) => k.id === id)) throw new ApiError(404, 'Client not found.');
  return [...(assignments.get(id) ?? [])];
}

export async function moveClients(r: MoveRequest): Promise<MoveResult> {
  await delay(700);
  const target = consultants.find((c) => c.id === r.toConsultantId);
  if (!target || target.status !== 'ACTIVE') throw new ApiError(422, 'Consultant is not active.');
  if (r.fromConsultantId === r.toConsultantId) throw new ApiError(400, 'Same consultant.');
  if (!r.reason.trim()) throw new ApiError(400, 'Reason required.');
  const chosen = clients.filter((c) => r.clientIds.includes(c.id));
  if (chosen.some((c) => (c.consultant?.id ?? null) !== r.fromConsultantId)) {
    throw new ApiError(409, 'Ownership changed.');
  }
  const at = new Date().toISOString();
  const batchId = chosen.length > 1 ? `batch-${Date.now()}` : null;
  for (const c of chosen) {
    const entry: AssignmentEntry = {
      changedAt: at,
      changeType:
        r.fromConsultantId === null ? 'MANUAL' : chosen.length > 1 ? 'BULK_TRANSFER' : 'TRANSFER',
      fromConsultant: c.consultant && {
        id: c.consultant.id,
        consultantCode: c.consultant.consultantCode,
        fullName: c.consultant.fullName,
      },
      toConsultant: ref(target),
      reason: r.reason.trim(),
      changedBy: { id: 1, email: who() },
      batchId,
    };
    assignments.set(c.id, [entry, ...(assignments.get(c.id) ?? [])]);
    c.consultant = link(target);
    c.version += 1;
    c.updatedAt = at;
  }
  return { moved: chosen.length };
}

export async function removeClient(id: number, reason: string): Promise<void> {
  await delay(500);
  if (!reason.trim()) throw new ApiError(400, 'Reason required.');
  const i = clients.findIndex((k) => k.id === id);
  if (i === -1) throw new ApiError(404, 'Client not found.');
  clients.splice(i, 1); // the real server would archive instead of erasing
}

// ---------- Import (invented preview: the file's contents are never read in mock mode) ----------
const DEMO_IMPORT_ID = 'demo-import';

export async function previewImport(file: File): Promise<ImportPreview> {
  await delay(900);
  return {
    id: DEMO_IMPORT_ID,
    fileName: file.name,
    totalRows: 120,
    newRows: 84,
    updatedRows: 18,
    unchangedRows: 9,
    invalidRows: 5,
    conflictRows: 4,
    issues: [
      {
        row: 6,
        idNumberMasked: '*********0412',
        kind: 'INVALID_ID',
        message: 'ID number must be 13 digits.',
      },
      {
        row: 19,
        idNumberMasked: '*******0388',
        kind: 'INVALID_ID',
        message: 'ID number must be 13 digits.',
      },
      {
        row: 31,
        idNumberMasked: '*********0127',
        kind: 'DUPLICATE_IN_FILE',
        message: 'Also appears on row 12. Fix the duplicate and upload again.',
      },
      {
        row: 44,
        idNumberMasked: '*********0560',
        kind: 'UNKNOWN_CONSULTANT',
        message: 'Consultant code "C-099" is not recognised.',
      },
      {
        row: 58,
        idNumberMasked: '*********0731',
        kind: 'INACTIVE_CONSULTANT',
        message: 'Consultant code "C-014" is suspended. Choose an active consultant.',
      },
      {
        row: 63,
        idNumberMasked: '*********0845',
        kind: 'ASSIGNMENT_CONFLICT',
        message: 'Already assigned to a different consultant. A manager must decide.',
      },
      {
        row: 77,
        idNumberMasked: '*********0292',
        kind: 'ASSIGNMENT_CONFLICT',
        message: 'Already assigned to a different consultant. A manager must decide.',
      },
      {
        row: 90,
        idNumberMasked: '*********0674',
        kind: 'ASSIGNMENT_CONFLICT',
        message: 'Already assigned to a different consultant. A manager must decide.',
      },
      {
        row: 101,
        idNumberMasked: '*********0519',
        kind: 'ASSIGNMENT_CONFLICT',
        message: 'Already assigned to a different consultant. A manager must decide.',
      },
    ],
  };
}

export async function confirmImport(id: string): Promise<ImportResult> {
  await delay(1200);
  if (id !== DEMO_IMPORT_ID) throw new ApiError(404, 'Unknown import.');
  return { imported: 102, unchanged: 9, skipped: 9 };
}
