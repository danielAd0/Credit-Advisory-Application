// Shapes match docs/api-auth-consultants-clients.md (the backend contract).

export type Role = 'ADMIN' | 'MANAGER' | 'CONSULTANT';
export type ConsultantStatus = 'ACTIVE' | 'INACTIVE' | 'SUSPENDED';

/** GET /api/auth/me and POST /api/auth/login (contract section 2). */
export interface Session {
  id: number;
  email: string;
  roles: Role[];
  consultant: { id: number; consultantCode: string; fullName: string } | null;
}

/** Every list endpoint returns one page (contract section 1). */
export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

/** The Consultant object (contract section 3). */
export interface ConsultantSummary {
  id: number;
  consultantCode: string;
  fullName: string;
  status: ConsultantStatus;
  statusChangedAt: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

/** The Client list item (contract section 4). Never contains the full ID number. */
export interface ClientSummary {
  id: number;
  idNumberMasked: string;
  firstName: string;
  lastName: string;
  email: string | null;
  phone: string | null;
  consultant: {
    id: number;
    consultantCode: string;
    fullName: string;
    status: ConsultantStatus;
  } | null;
  version: number;
  createdAt: string;
  updatedAt: string;
}
