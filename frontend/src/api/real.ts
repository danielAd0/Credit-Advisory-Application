import { assignClients, transferClients } from './admin';
import { ApiError, api, fetchSession, signIn, signOut } from './http';
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
  MoveRequest,
  MoveResult,
  NewClient,
  PageResponse,
} from './types';

export const getSession = fetchSession;
export const login = signIn;
export const logout = signOut;

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

export const listConsultants = () =>
  api<PageResponse<ConsultantSummary>>('/api/consultants?size=100').then((page) => page.items);

export async function getClient(id: number): Promise<ClientDetail> {
  const { idNumber: _drop, ...client } = await api<ClientDetail & { idNumber?: string }>(
    `/api/clients/${id}`,
  );
  return client;
}

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

export async function updateClient(id: number, u: ClientUpdate): Promise<ClientSummary> {
  let version = u.version;
  const correcting = Boolean(u.idNumber);
  if (u.idNumber) {
    const corrected = await api<ClientSummary>(`/api/clients/${id}/id-number-correction`, {
      method: 'POST',
      body: JSON.stringify({
        idNumber: u.idNumber,
        reason: u.idNumberReason ?? '',
        version,
      }),
    });
    version = corrected.version;
  }
  try {
    const { idNumber: _drop, ...client } = await api<ClientSummary & { idNumber?: string }>(
      `/api/clients/${id}`,
      {
        method: 'PATCH',
        body: JSON.stringify({
          firstName: u.firstName,
          lastName: u.lastName,
          email: u.email || null,
          phone: u.phone || null,
          version,
        }),
      },
    );
    return client;
  } catch (err) {
    if (correcting && err instanceof ApiError) {
      throw new ApiError(err.status, err.message, {
        type: null,
        detail:
          'The ID number was corrected, but the other changes were not saved. Reopen the client and save them again.',
      });
    }
    throw err;
  }
}

export const listAssignments = (id: number) =>
  api<AssignmentEntry[]>(`/api/clients/${id}/assignments`);

export async function moveClients(r: MoveRequest): Promise<MoveResult> {
  if (r.fromConsultantId === null) {
    const res = await assignClients(r.toConsultantId, r.clientIds, r.reason);
    return { moved: res.assignedCount };
  }
  const res = await transferClients(r.fromConsultantId, r.toConsultantId, r.clientIds, r.reason);
  return { moved: res.transferredCount };
}

export const removeClient = (id: number, reason: string) =>
  api<unknown>(`/api/clients/${id}/unassign`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  }).then(() => undefined);

export const getDashboardSummary = () => api<DashboardSummary>('/api/dashboard/summary');

export const changePassword = (r: ChangePasswordRequest) =>
  api<void>('/api/auth/change-password', {
    method: 'POST',
    body: JSON.stringify(r),
  });

export const listCases = (clientId: number) =>
  api<PageResponse<CaseListItem>>(`/api/cases?clientId=${clientId}&size=100`).then((p) => p.items);

export const getCase = (id: number) => api<CaseDetail>(`/api/cases/${id}`);

export const createCase = (clientId: number, title: string) =>
  api<CaseDetail>('/api/cases', {
    method: 'POST',
    body: JSON.stringify({ clientId, title }),
  });

export const updateCase = (id: number, u: { title: string; status: string; version: number }) =>
  api<CaseDetail>(`/api/cases/${id}`, {
    method: 'PATCH',
    body: JSON.stringify(u),
  });

export const addCaseNote = (id: number, note: string) =>
  api<CaseDetail['notes'][number]>(`/api/cases/${id}/notes`, {
    method: 'POST',
    body: JSON.stringify({ note }),
  });
