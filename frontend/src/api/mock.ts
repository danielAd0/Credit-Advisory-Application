import { ApiError, STALE_VERSION } from './http';
import { mockEmployerRef, mockQuotationRef } from './mockCatalogue';
import { mockMfaSignedIn, mockMfaSignedOut, mockMfaStatus } from './mockMfaState';
import type {
  AssignmentEntry,
  CaseDetail,
  CaseListItem,
  CaseNote,
  ChangePasswordRequest,
  DashboardSummary,
  PageResponse,
  ClientDetail,
  ClientFields,
  ClientFilter,
  ClientSummary,
  ClientUpdate,
  ConsultantRef,
  ConsultantSummary,
  MoveRequest,
  MoveResult,
  NewClient,
  Role,
  Session,
} from './types';

export const delay = (ms = 250) => new Promise((r) => setTimeout(r, ms));
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

export const consultants: ConsultantSummary[] = [
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

// Same ids and names as the mock employers and product quotations in mockCatalogue.ts.
// Written out here because that file imports this one.
const mockEmployers = [
  { id: 1, name: 'Sample Municipality', active: true },
  { id: 2, name: 'Example Hospital', active: true },
  { id: 3, name: 'Demo Mining Co', active: true },
];
const mockQuotations = [
  { id: 1, name: 'Sample Branch North', active: true },
  { id: 2, name: 'Sample Branch South', active: true },
];

export const clients: ClientSummary[] = Array.from({ length: 8 }, (_, i) => {
  const c = i === 7 ? null : consultants[i % 3];
  const debitOrder = i === 5;
  return {
    id: i + 1,
    idNumberMasked: `*********${String(i + 1).padStart(4, '0')}`,
    firstName: 'Sample',
    lastName: `Client ${String(i + 1).padStart(2, '0')}`,
    email: `client${i + 1}@example.com`,
    phone: `+2782000000${i + 1}`,
    workPhone: i % 2 === 0 ? `+2711555000${i + 1}` : null,
    homePhone: i % 3 === 0 ? `+2712555000${i + 1}` : null,
    employer: debitOrder || i === 7 ? null : mockEmployers[i % 3],
    productQuotation: i === 7 ? null : mockQuotations[i % 2],
    fileNumber: `00${1000 + i}`,
    fileId: i === 7 ? null : String(5000 + i),
    paymentMethod: debitOrder ? 'DEBIT_ORDER' : null,
    latestSale:
      i >= 6
        ? null
        : {
            date: `2026-10-${String(10 + i).padStart(2, '0')}`,
            paymentTerm: 'Once off payment',
            structure: 'Vital',
          },
    consultant: c && link(c),
    version: 0,
    createdAt: when(20 - i),
    updatedAt: when(20 - i),
  };
});

let nextId = clients.length + 1;

const assignments = new Map<number, AssignmentEntry[]>();

const KEY = 'mockUser';
const who = () => sessionStorage.getItem(KEY) ?? 'unknown';

const MUST_CHANGE = 'mockMustChange';

function mockSession(email: string): Session {
  const role = (import.meta.env.VITE_MOCK_ROLE as Role | undefined) ?? 'MANAGER';
  const mine = role === 'CONSULTANT' ? ref(consultants[0]) : null;
  return {
    id: 1,
    email,
    roles: [role],
    consultant: mine,
    mustChangePassword: sessionStorage.getItem(MUST_CHANGE) === 'yes',
    mfa: mockMfaStatus(),
  };
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
  if (email.toLowerCase().startsWith('new')) sessionStorage.setItem(MUST_CHANGE, 'yes');
  mockMfaSignedIn();
  return mockSession(email);
}

export async function changePassword(r: ChangePasswordRequest): Promise<void> {
  await delay(600);
  if (r.currentPassword === 'wrong') {
    throw new ApiError(400, 'Your current password is not correct.', {
      type: 'urn:credit-advisory:auth:wrong-current-password',
      detail: 'Your current password is not correct.',
    });
  }
  sessionStorage.removeItem(MUST_CHANGE);
}

export async function logout(): Promise<void> {
  sessionStorage.removeItem(KEY);
  sessionStorage.removeItem(MUST_CHANGE);
  mockMfaSignedOut();
}

export async function listClientsPage(
  filter: ClientFilter,
  page: number,
  size = 25,
): Promise<PageResponse<ClientSummary>> {
  await delay();
  const q = (filter.search ?? '').trim().toLowerCase();
  // Like the server: a search of digits only also matches a File number or File ID exactly.
  const fileReference = /^[0-9]{1,20}$/.test(q) ? q : null;
  const all = clients.filter((c) => {
    const byName = `${c.firstName} ${c.lastName}`.toLowerCase().includes(q);
    const byFile =
      fileReference !== null && (c.fileNumber === fileReference || c.fileId === fileReference);
    if (q && !byName && !byFile) return false;
    if (filter.unassigned && c.consultant) return false;
    if (filter.consultantId !== undefined && c.consultant?.id !== filter.consultantId) return false;
    return true;
  });
  return {
    items: all.slice(page * size, page * size + size),
    page,
    size,
    totalItems: all.length,
    totalPages: Math.max(1, Math.ceil(all.length / size)),
  };
}

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

/** Phones as the server stores them: 082 123 4567 becomes +27821234567. */
function mockPhone(raw: string): string | null {
  const compact = raw.replace(/[\s.()-]/g, '');
  if (!compact) return null;
  if (/^0\d{9}$/.test(compact)) return `+27${compact.slice(1)}`;
  if (/^\+[1-9]\d{7,14}$/.test(compact)) return compact;
  throw new ApiError(400, 'Not a valid phone number.', {
    type: 'urn:credit-advisory:validation:invalid-phone',
    detail:
      'Not a valid phone number. Use a South African number such as 082 123 4567, or an international number starting with +.',
  });
}

/** The shared fields, checked like the server does. current is the client being edited. */
function mockFields(f: ClientFields, current: ClientSummary | null) {
  return {
    firstName: f.firstName.trim(),
    lastName: f.lastName.trim(),
    email: f.email.trim() || null,
    phone: mockPhone(f.phone),
    workPhone: mockPhone(f.workPhone),
    homePhone: mockPhone(f.homePhone),
    employer: mockEmployerRef(f.employerId, current?.employer?.id),
    productQuotation: mockQuotationRef(f.productQuotationId, current?.productQuotation?.id),
    fileNumber: f.fileNumber.trim() || null,
    fileId: f.fileId.trim() || null,
    paymentMethod: f.paymentMethod,
  };
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
    ...mockFields(c, null),
    latestSale: null,
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
  if (u.version !== c.version) throw new ApiError(409, 'Stale version.', { type: STALE_VERSION });
  if (u.idNumber) {
    const masked = `*********${u.idNumber.slice(-4)}`;
    if (clients.some((k) => k.id !== id && k.idNumberMasked === masked)) {
      throw new ApiError(409, 'Duplicate');
    }
    c.idNumberMasked = masked;
  }
  Object.assign(c, mockFields(u, c));
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
  const c = clients.find((k) => k.id === id);
  if (!c) throw new ApiError(404, 'Client not found.');
  if (!c.consultant) {
    throw new ApiError(409, 'This client has no consultant.', {
      type: 'urn:credit-advisory:unassign:not-assigned',
      detail: 'This client has no consultant.',
    });
  }
  const open = cases.filter((k) => k.client.id === id && k.status !== 'CLOSED').length;
  if (open > 0) {
    const detail = `This client has ${open} case${open === 1 ? '' : 's'} that ${
      open === 1 ? 'is' : 'are'
    } not closed. Close ${open === 1 ? 'it' : 'them'} first, or transfer the client instead.`;
    throw new ApiError(409, detail, { type: 'urn:credit-advisory:unassign:open-cases', detail });
  }
  const at = new Date().toISOString();
  const entry: AssignmentEntry = {
    changedAt: at,
    changeType: 'UNASSIGN',
    fromConsultant: {
      id: c.consultant.id,
      consultantCode: c.consultant.consultantCode,
      fullName: c.consultant.fullName,
    },
    toConsultant: null,
    reason: reason.trim(),
    changedBy: { id: 1, email: who() },
    batchId: null,
  };
  assignments.set(id, [entry, ...(assignments.get(id) ?? [])]);
  c.consultant = null;
  c.version += 1;
  c.updatedAt = at;
}

export async function getDashboardSummary(): Promise<DashboardSummary> {
  await delay(300);
  const role = (import.meta.env.VITE_MOCK_ROLE as Role | undefined) ?? 'MANAGER';
  const count = (status: string) => cases.filter((c) => c.status === status).length;
  const caseCounts = {
    open: count('OPEN'),
    inProgress: count('IN_PROGRESS'),
    closed: count('CLOSED'),
  };
  if (role === 'CONSULTANT') {
    return {
      scope: 'OWN',
      totalClients: clients.filter((c) => c.consultant?.id === 1).length,
      unassignedClients: null,
      clientsNeedingHandover: null,
      consultants: null,
      cases: caseCounts,
    };
  }
  const by = (status: string) => consultants.filter((c) => c.status === status).length;
  return {
    scope: 'ALL',
    totalClients: clients.length,
    unassignedClients: clients.filter((c) => !c.consultant).length,
    clientsNeedingHandover: clients.filter((c) => c.consultant && c.consultant.status !== 'ACTIVE')
      .length,
    consultants: {
      active: by('ACTIVE'),
      inactive: by('INACTIVE'),
      suspended: by('SUSPENDED'),
    },
    cases: caseCounts,
  };
}

const caseUser = () => ({ id: 1, email: who(), consultantName: null });

const cases: CaseDetail[] = [
  {
    id: 1,
    client: {
      id: 1,
      idNumberMasked: clients[0].idNumberMasked,
      firstName: clients[0].firstName,
      lastName: clients[0].lastName,
      consultant: clients[0].consultant,
    },
    title: 'Debt review application',
    status: 'IN_PROGRESS',
    openedAt: when(21),
    closedAt: null,
    createdBy: {
      id: 1,
      email: 'consultant@example.com',
      consultantName: 'Boitumelo Khumalo',
    },
    version: 1,
    createdAt: when(21),
    updatedAt: when(22),
    notes: [
      {
        id: 1,
        note: 'Payslip received.',
        author: {
          id: 1,
          email: 'consultant@example.com',
          consultantName: 'Boitumelo Khumalo',
        },
        createdAt: when(22),
      },
    ],
  },
  {
    id: 2,
    client: {
      id: 1,
      idNumberMasked: clients[0].idNumberMasked,
      firstName: clients[0].firstName,
      lastName: clients[0].lastName,
      consultant: clients[0].consultant,
    },
    title: 'Credit report query',
    status: 'CLOSED',
    openedAt: when(5),
    closedAt: when(9),
    createdBy: {
      id: 1,
      email: 'consultant@example.com',
      consultantName: 'Boitumelo Khumalo',
    },
    version: 2,
    createdAt: when(5),
    updatedAt: when(9),
    notes: [],
  },
];

let nextCaseId = 3;
let nextNoteId = 2;

const asItem = (c: CaseDetail): CaseListItem => {
  const { notes: _notes, ...item } = c;
  return item;
};

export async function listCases(clientId: number): Promise<CaseListItem[]> {
  await delay(300);
  return cases.filter((c) => c.client.id === clientId).map(asItem);
}

export async function getCase(id: number): Promise<CaseDetail> {
  await delay(250);
  const c = cases.find((k) => k.id === id);
  if (!c) throw new ApiError(404, 'Case not found.');
  return { ...c, notes: [...c.notes] };
}

export async function createCase(clientId: number, title: string): Promise<CaseDetail> {
  await delay(400);
  const client = clients.find((k) => k.id === clientId);
  if (!client) throw new ApiError(404, 'Client not found.');
  if (!title.trim()) throw new ApiError(400, 'Title required.');
  const now = new Date().toISOString();
  const created: CaseDetail = {
    id: nextCaseId++,
    client: {
      id: client.id,
      idNumberMasked: client.idNumberMasked,
      firstName: client.firstName,
      lastName: client.lastName,
      consultant: client.consultant,
    },
    title: title.trim(),
    status: 'OPEN',
    openedAt: now,
    closedAt: null,
    createdBy: caseUser(),
    version: 0,
    createdAt: now,
    updatedAt: now,
    notes: [],
  };
  cases.unshift(created);
  return { ...created };
}

export async function updateCase(
  id: number,
  u: { title: string; status: string; version: number },
): Promise<CaseDetail> {
  await delay(400);
  const c = cases.find((k) => k.id === id);
  if (!c) throw new ApiError(404, 'Case not found.');
  if (u.version !== c.version) throw new ApiError(409, 'Stale version.', { type: STALE_VERSION });
  c.title = u.title.trim();
  c.status = u.status;
  c.closedAt = u.status === 'CLOSED' ? new Date().toISOString() : null;
  c.version += 1;
  c.updatedAt = new Date().toISOString();
  return { ...c, notes: [...c.notes] };
}

export async function addCaseNote(id: number, note: string): Promise<CaseNote> {
  await delay(400);
  const c = cases.find((k) => k.id === id);
  if (!c) throw new ApiError(404, 'Case not found.');
  if (c.status === 'CLOSED') {
    throw new ApiError(409, 'This case is closed. Reopen it to add a note.', {
      type: 'urn:credit-advisory:case:closed',
      detail: 'This case is closed. Reopen it to add a note.',
    });
  }
  const created: CaseNote = {
    id: nextNoteId++,
    note: note.trim(),
    author: caseUser(),
    createdAt: new Date().toISOString(),
  };
  c.notes.unshift(created);
  return created;
}
