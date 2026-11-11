import { api } from './http';
import type { ClientSummary, ConsultantStatus, PageResponse, Role } from './types';

export interface Consultant {
  id: number;
  consultantCode: string;
  fullName: string;
  status: ConsultantStatus;
  statusChangedAt: string;
  clientCount: number;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface UserAccount {
  id: number;
  email: string;
  enabled: boolean;
  roles: Role[];
  consultant: {
    id: number;
    consultantCode: string;
    fullName: string;
    status: ConsultantStatus;
  } | null;
  locked: boolean;
  lockedUntil: string | null;
  lastLoginAt: string | null;
  mustChangePassword: boolean;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface ConsultantAccount {
  consultant: Consultant;
  user: UserAccount;
}

export interface Product {
  id: number;
  code: string;
  name: string;
  standardPrice: number | null;
  countsTowardQuotation: boolean;
  countsTowardBanked: boolean;
  active: boolean;
  displayOrder: number;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface Employer {
  id: number;
  name: string;
  company: string | null;
  employerType: string | null;
  active: boolean;
  version: number;
  createdAt: string;
  updatedAt: string;
}

/** A product quotation (the old spreadsheets' C_BRANCHNAME): a list admins manage. */
export interface ProductQuotation {
  id: number;
  name: string;
  active: boolean;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface AssignClientsResult {
  batchId: string | null;
  assignedCount: number;
  consultant: { id: number; consultantCode: string; fullName: string };
}

// ---------- helpers ----------

const json = (method: 'POST' | 'PATCH', body: unknown): RequestInit => ({
  method,
  body: JSON.stringify(body),
});

function query(params: Record<string, string | number | boolean | undefined>): string {
  const q = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== '') q.set(key, String(value));
  }
  const s = q.toString();
  return s ? `?${s}` : '';
}

// ---------- consultants and their logins ----------

/** POST /api/consultant-accounts - consultant record + CONSULTANT login in one step (ADMIN). */
export const createConsultantAccount = (input: {
  consultantCode: string;
  fullName: string;
  email: string;
  temporaryPassword: string;
}) => api<ConsultantAccount>('/api/consultant-accounts', json('POST', input));

/** GET /api/consultants (MANAGER, ADMIN). */
export const listConsultants = (
  params: { search?: string; status?: ConsultantStatus; page?: number; size?: number } = {},
) => api<PageResponse<Consultant>>(`/api/consultants${query(params)}`);

/** PATCH /api/consultants/{id}/status (ADMIN). */
export const changeConsultantStatus = (
  id: number,
  status: ConsultantStatus,
  reason: string,
  version: number,
) => api<Consultant>(`/api/consultants/${id}/status`, json('PATCH', { status, reason, version }));

/** GET /api/users (ADMIN) - optionally only users with one role. Sorted by email. */
export const listUsers = (
  params: { search?: string; role?: Role; page?: number; size?: number } = {},
) => api<PageResponse<UserAccount>>(`/api/users${query(params)}`);

/** POST /api/users (ADMIN) - a login with any roles; CONSULTANT also needs consultantId. */
export const createUser = (input: {
  email: string;
  temporaryPassword: string;
  roles: Role[];
  consultantId?: number | null;
}) => api<UserAccount>('/api/users', json('POST', input));

/** PATCH /api/users/{id}/roles (ADMIN) - replaces the roles; signs the user out. */
export const changeUserRoles = (id: number, roles: Role[], version: number) =>
  api<UserAccount>(`/api/users/${id}/roles`, json('PATCH', { roles, version }));

/** PATCH /api/users/{id}/consultant (ADMIN) - link a consultant record, or null to unlink. */
export const changeUserConsultant = (id: number, consultantId: number | null, version: number) =>
  api<UserAccount>(`/api/users/${id}/consultant`, json('PATCH', { consultantId, version }));

/** PATCH /api/users/{id}/enabled (ADMIN) - disabling signs the user out. */
export const setUserEnabled = (id: number, enabled: boolean, version: number) =>
  api<UserAccount>(`/api/users/${id}/enabled`, json('PATCH', { enabled, version }));

/** POST /api/users/{id}/password-reset (ADMIN) - sets a new temporary password and unlocks. */
export const resetPassword = (userId: number, temporaryPassword: string) =>
  api<UserAccount>(`/api/users/${userId}/password-reset`, json('POST', { temporaryPassword }));

// ---------- products ----------

export const listProducts = (
  params: { search?: string; active?: boolean; page?: number; size?: number } = {},
) => api<PageResponse<Product>>(`/api/products${query(params)}`);

export const createProduct = (input: {
  code: string;
  name: string;
  standardPrice: number | null;
  countsTowardQuotation: boolean;
  countsTowardBanked: boolean;
  displayOrder?: number;
}) => api<Product>('/api/products', json('POST', input));

export const updateProduct = (
  id: number,
  input: {
    name: string;
    standardPrice: number | null;
    countsTowardQuotation: boolean;
    countsTowardBanked: boolean;
    displayOrder: number;
    version: number;
  },
) => api<Product>(`/api/products/${id}`, json('PATCH', input));

export const setProductActive = (id: number, active: boolean, version: number) =>
  api<Product>(`/api/products/${id}/active`, json('PATCH', { active, version }));

// ---------- employers ----------

export const listEmployers = (
  params: {
    search?: string;
    active?: boolean;
    employerType?: string;
    page?: number;
    size?: number;
  } = {},
) => api<PageResponse<Employer>>(`/api/employers${query(params)}`);

export const createEmployer = (input: {
  name: string;
  company?: string | null;
  employerType?: string | null;
}) => api<Employer>('/api/employers', json('POST', input));

export const updateEmployer = (
  id: number,
  input: { name: string; company: string | null; employerType: string | null; version: number },
) => api<Employer>(`/api/employers/${id}`, json('PATCH', input));

export const setEmployerActive = (id: number, active: boolean, version: number) =>
  api<Employer>(`/api/employers/${id}/active`, json('PATCH', { active, version }));

// ---------- product quotations ----------

/** GET /api/product-quotations - everyone signed in. Sorted by name. */
export const listProductQuotations = (
  params: { search?: string; active?: boolean; page?: number; size?: number } = {},
) => api<PageResponse<ProductQuotation>>(`/api/product-quotations${query(params)}`);

/** POST /api/product-quotations (ADMIN). */
export const createProductQuotation = (input: { name: string }) =>
  api<ProductQuotation>('/api/product-quotations', json('POST', input));

/** PATCH /api/product-quotations/{id} (ADMIN): rename. */
export const updateProductQuotation = (id: number, input: { name: string; version: number }) =>
  api<ProductQuotation>(`/api/product-quotations/${id}`, json('PATCH', input));

/** PATCH /api/product-quotations/{id}/active (ADMIN). */
export const setProductQuotationActive = (id: number, active: boolean, version: number) =>
  api<ProductQuotation>(`/api/product-quotations/${id}/active`, json('PATCH', { active, version }));

// ---------- client assignment ----------

/** GET /api/clients?unassigned=true (MANAGER, ADMIN). */
export const listUnassignedClients = (
  params: { search?: string; page?: number; size?: number } = {},
) => api<PageResponse<ClientSummary>>(`/api/clients${query({ ...params, unassigned: true })}`);

/** POST /api/client-assignments - give unassigned clients to an ACTIVE consultant (MANAGER, ADMIN). */
export const assignClients = (consultantId: number, clientIds: number[], reason: string) =>
  api<AssignClientsResult>(
    '/api/client-assignments',
    json('POST', { consultantId, clientIds, reason }),
  );

/** POST /api/client-transfers - move clients that already have a consultant (MANAGER, ADMIN). */
export const transferClients = (
  fromConsultantId: number,
  toConsultantId: number,
  clientIds: number[],
  reason: string,
) =>
  api<{ batchId: string | null; transferredCount: number }>(
    '/api/client-transfers',
    json('POST', { fromConsultantId, toConsultantId, clientIds, reason }),
  );
