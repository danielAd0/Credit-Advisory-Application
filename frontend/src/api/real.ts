import { assignClients, transferClients } from './admin';
import { api, fetchSession, signIn, signOut } from './http';
import type {
  AssignmentEntry,
  CaseDetail,
  CaseListItem,
  ChangePasswordRequest,
  DashboardSummary,
  ClientDetail,
  ClientFilter,
  ClientSummary,
  ClientUpdate,
  ConsultantSummary,
  ImportPreview,
  ImportResult,
  MoveRequest,
  MoveResult,
  NewClient,
  PageResponse,
} from './types';

export const getSession = fetchSession;
export const login = signIn;
export const logout = signOut;

/** GET /api/clients (contract section 4): one page, 0-based. */
export function listClientsPage(
  filter: ClientFilter,
  page: number,
  size = 25,
): Promise<PageResponse<ClientSummary>> {
  const q = new URLSearchParams({ page: String(page), size: String(size) });
  if (filter.search) q.set('search', filter.search);
  if (filter.consultantId !== undefined) q.set('consultantId', String(filter.consultantId));
  if (filter.unassigned) q.set('unassigned', 'true');
  return api<PageResponse<ClientSummary>>(`/api/clients?${q.toString()}`);
}

/** GET /api/consultants (contract section 3). Returns the first page's items. */
export const listConsultants = () =>
  api<PageResponse<ConsultantSummary>>('/api/consultants?size=100').then((page) => page.items);

/**
 * GET /api/clients/{id}. The server also returns the full ID number and audits the view; it is
 * dropped here so it never sits in the page's memory.
 */
export async function getClient(id: number): Promise<ClientDetail> {
  const { idNumber: _drop, ...client } = await api<ClientDetail & { idNumber?: string }>(
    `/api/clients/${id}`,
  );
  return client;
}

/** POST /api/clients. */
export async function createClient(c: NewClient): Promise<ClientSummary> {
  const { idNumber: _drop, ...client } = await api<ClientSummary & { idNumber?: string }>(
    '/api/clients',
    {
      method: 'POST',
      body: JSON.stringify({
        idNumber: c.idNumber,
        firstName: c.firstName,
        lastName: c.lastName,
        email: c.email || null,
        phone: c.phone || null,
        consultantId: c.consultantId,
      }),
    },
  );
  return client;
}

/** PATCH /api/clients/{id}. The ID number cannot be changed here (contract section 4). */
export async function updateClient(id: number, u: ClientUpdate): Promise<ClientSummary> {
  const { idNumber: _drop, ...client } = await api<ClientSummary & { idNumber?: string }>(
    `/api/clients/${id}`,
    {
      method: 'PATCH',
      body: JSON.stringify({
        firstName: u.firstName,
        lastName: u.lastName,
        email: u.email || null,
        phone: u.phone || null,
        version: u.version,
      }),
    },
  );
  return client;
}

/** GET /api/clients/{id}/assignments (MANAGER, ADMIN). */
export const listAssignments = (id: number) =>
  api<AssignmentEntry[]>(`/api/clients/${id}/assignments`);

/** Unassigned clients go through /api/client-assignments, assigned ones through /api/client-transfers. */
export async function moveClients(r: MoveRequest): Promise<MoveResult> {
  if (r.fromConsultantId === null) {
    const res = await assignClients(r.toConsultantId, r.clientIds, r.reason);
    return { moved: res.assignedCount };
  }
  const res = await transferClients(r.fromConsultantId, r.toConsultantId, r.clientIds, r.reason);
  return { moved: res.transferredCount };
}

// ---------- Not in the backend yet. The screens are hidden until `features` is switched on. ----------

export const removeClient = (id: number, reason: string) =>
  api<void>(`/api/clients/${id}/remove`, { method: 'POST', body: JSON.stringify({ reason }) });

export function previewImport(file: File): Promise<ImportPreview> {
  const form = new FormData();
  form.append('file', file);
  return api<ImportPreview>('/api/imports/preview', { method: 'POST', body: form });
}

export const confirmImport = (id: string) =>
  api<ImportResult>(`/api/imports/${encodeURIComponent(id)}/confirm`, { method: 'POST' });

/** GET /api/dashboard/summary (contract section 7). */
export const getDashboardSummary = () => api<DashboardSummary>('/api/dashboard/summary');

/** POST /api/auth/change-password (contract section 2). 204 on success. */
export const changePassword = (r: ChangePasswordRequest) =>
  api<void>('/api/auth/change-password', { method: 'POST', body: JSON.stringify(r) });

// ---------- Credit cases (contract section 9) ----------

/** GET /api/cases?clientId= : the cases tab on a client page. */
export const listCases = (clientId: number) =>
  api<PageResponse<CaseListItem>>(`/api/cases?clientId=${clientId}&size=100`).then((p) => p.items);

export const getCase = (id: number) => api<CaseDetail>(`/api/cases/${id}`);

export const createCase = (clientId: number, title: string) =>
  api<CaseDetail>('/api/cases', { method: 'POST', body: JSON.stringify({ clientId, title }) });

export const updateCase = (id: number, u: { title: string; status: string; version: number }) =>
  api<CaseDetail>(`/api/cases/${id}`, { method: 'PATCH', body: JSON.stringify(u) });

export const addCaseNote = (id: number, note: string) =>
  api<CaseDetail['notes'][number]>(`/api/cases/${id}/notes`, {
    method: 'POST',
    body: JSON.stringify({ note }),
  });
