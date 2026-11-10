export type Role = 'ADMIN' | 'MANAGER' | 'CONSULTANT';
export type ConsultantStatus = 'ACTIVE' | 'INACTIVE' | 'SUSPENDED';

/**
 * Where this session stands with the second sign-in step (contract section 2, "Two-step sign-in").
 * SETUP_REQUIRED and CODE_REQUIRED: only the two-step screen works until it is done.
 */
export type MfaStatus = 'NOT_REQUIRED' | 'SETUP_REQUIRED' | 'CODE_REQUIRED' | 'VERIFIED';

export interface Session {
  id: number;
  email: string;
  roles: Role[];
  consultant: { id: number; consultantCode: string; fullName: string } | null;
  mustChangePassword: boolean;
  mfa: MfaStatus;
}

export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

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

/** An employer or product quotation on a client. active is false once an admin deactivates it. */
export interface NamedRef {
  id: number;
  name: string;
  active: boolean;
}

/** Only DEBIT_ORDER for now ("DEBIT ORDER" in the old spreadsheets). */
export type PaymentMethod = 'DEBIT_ORDER';

/** Payment term and structure come from the client's most recent sale. */
export interface LatestSale {
  date: string;
  paymentTerm: string | null;
  structure: string | null;
}

export interface ClientSummary {
  id: number;
  idNumberMasked: string;
  firstName: string;
  lastName: string;
  email: string | null;
  /** Cellphone. All phones come back in international form, e.g. +27821234567. */
  phone: string | null;
  workPhone: string | null;
  homePhone: string | null;
  employer: NamedRef | null;
  productQuotation: NamedRef | null;
  /** Digits only, kept as text so leading zeros survive. */
  fileNumber: string | null;
  fileId: string | null;
  paymentMethod: PaymentMethod | null;
  /** null when the client has no sales yet. */
  latestSale: LatestSale | null;
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

export type ClientDetail = ClientSummary;

export interface ConsultantRef {
  id: number;
  consultantCode: string;
  fullName: string;
}

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

/** The fields the add and edit forms share. Empty text means none. */
export interface ClientFields {
  firstName: string;
  lastName: string;
  email: string;
  phone: string;
  workPhone: string;
  homePhone: string;
  employerId: number | null;
  productQuotationId: number | null;
  fileNumber: string;
  fileId: string;
  paymentMethod: PaymentMethod | null;
}

export interface NewClient extends ClientFields {
  idNumber: string;
  consultantId: number | null;
}

/** Every field is sent: an empty one clears it. */
export interface ClientUpdate extends ClientFields {
  version: number;
  idNumber?: string;
  idNumberReason?: string;
}

export interface ClientFilter {
  search?: string;
  consultantId?: number;
  unassigned?: boolean;
}

export interface MoveRequest {
  fromConsultantId: number | null;
  toConsultantId: number;
  clientIds: number[];
  reason: string;
}

export interface MoveResult {
  moved: number;
}

export interface DashboardSummary {
  scope: 'ALL' | 'OWN';
  totalClients: number;
  unassignedClients: number | null;
  clientsNeedingHandover: number | null;
  consultants: { active: number; inactive: number; suspended: number } | null;
  cases: { open: number; inProgress: number; closed: number };
}

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

export interface CaseDetail extends CaseListItem {
  notes: CaseNote[];
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}
