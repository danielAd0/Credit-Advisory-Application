// Shapes match docs/api-auth-consultants-clients.md (the backend contract).

export type Role = 'ADMIN' | 'MANAGER' | 'CONSULTANT';
export type ConsultantStatus = 'ACTIVE' | 'INACTIVE' | 'SUSPENDED';

/** GET /api/auth/me and POST /api/auth/login (contract section 2). */
export interface Session {
  id: number;
  email: string;
  roles: Role[];
  consultant: { id: number; consultantCode: string; fullName: string } | null;
  /** True after an admin set the password. Only the change-password screen works until it is false. */
  mustChangePassword: boolean;
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

/** The single-client object (contract section 4). The full ID number is dropped before it reaches the screens. */
export type ClientDetail = ClientSummary;

export interface ConsultantRef {
  id: number;
  consultantCode: string;
  fullName: string;
}

/** GET /api/clients/{id}/assignments (contract section 5). MANAGER and ADMIN only. */
export type AssignmentChangeType = 'IMPORT' | 'MANUAL' | 'TRANSFER' | 'BULK_TRANSFER' | 'UNASSIGN';

export interface AssignmentEntry {
  changedAt: string;
  changeType: AssignmentChangeType;
  fromConsultant: ConsultantRef | null;
  toConsultant: ConsultantRef | null;
  reason: string;
  changedBy: { id: number; email: string };
  batchId: string | null;
}

/** POST /api/clients (contract section 4). */
export interface NewClient {
  firstName: string;
  lastName: string;
  /** Sent once over HTTPS. Never kept in the browser and never shown again unmasked. */
  idNumber: string;
  email: string;
  phone: string;
  /** null = unassigned (managers) or "me" (consultants). */
  consultantId: number | null;
}

/** PATCH /api/clients/{id}: all four editable fields plus the version last read. */
export interface ClientUpdate {
  firstName: string;
  lastName: string;
  email: string;
  phone: string;
  version: number;
  /** Only used while the backend cannot correct ID numbers (see `features`). Managers and admins only. */
  idNumber?: string;
}

/** Which clients to list. A consultant is always limited to their own, whatever is sent. */
export interface ClientFilter {
  search?: string;
  consultantId?: number;
  unassigned?: boolean;
}

/** One handover. fromConsultantId null means the clients are unassigned today. */
export interface MoveRequest {
  fromConsultantId: number | null;
  toConsultantId: number;
  clientIds: number[];
  reason: string;
}

export interface MoveResult {
  moved: number;
}

// ---------- Excel import (no backend endpoint yet: see `features`) ----------

export type ImportIssueKind =
  | 'INVALID_ID'
  | 'DUPLICATE_IN_FILE'
  | 'UNKNOWN_CONSULTANT'
  | 'INACTIVE_CONSULTANT'
  | 'ASSIGNMENT_CONFLICT';

export interface ImportIssue {
  /** Row number in the workbook, as the person sees it in Excel. */
  row: number;
  /** Masked by the server. Full ID numbers are never returned in import reports. */
  idNumberMasked: string;
  kind: ImportIssueKind;
  /** Safe message written by the server: no names, no full ID numbers. */
  message: string;
}

export interface ImportPreview {
  id: string;
  fileName: string;
  totalRows: number;
  newRows: number;
  updatedRows: number;
  unchangedRows: number;
  invalidRows: number;
  /** Rows that would move a client away from a manager-approved consultant. Never applied automatically. */
  conflictRows: number;
  issues: ImportIssue[];
}

export interface ImportResult {
  imported: number;
  unchanged: number;
  skipped: number;
}

/** GET /api/dashboard/summary (contract section 7). Counts only. */
export interface DashboardSummary {
  /** ALL for managers and admins, OWN for a consultant. */
  scope: 'ALL' | 'OWN';
  totalClients: number;
  unassignedClients: number | null;
  clientsNeedingHandover: number | null;
  consultants: { active: number; inactive: number; suspended: number } | null;
  cases: { open: number; inProgress: number; closed: number };
}

// ---------- Credit cases (contract section 9) ----------

/** OPEN, IN_PROGRESS or CLOSED today. More may be added later, so an unknown value is shown as plain text. */
export type CaseStatus = string;

export interface CaseUser {
  id: number;
  email: string;
  consultantName: string | null;
}

export interface CaseListItem {
  id: number;
  client: {
    id: number;
    idNumberMasked: string;
    firstName: string;
    lastName: string;
    consultant: ClientSummary['consultant'];
  };
  title: string;
  status: CaseStatus;
  openedAt: string;
  closedAt: string | null;
  createdBy: CaseUser;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface CaseNote {
  id: number;
  note: string;
  author: CaseUser;
  createdAt: string;
}

/** Notes are newest first and can never be edited or deleted. */
export interface CaseDetail extends CaseListItem {
  notes: CaseNote[];
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}
