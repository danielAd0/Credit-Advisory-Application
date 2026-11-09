import * as mock from './mock';
import * as real from './real';

const useMock = import.meta.env.VITE_USE_MOCK === 'true';

export const data = useMock ? mock : real;
export { ApiError } from './http';
export { accounts } from './accounts';
export type { Consultant, ConsultantAccount, UserAccount } from './accounts';
export { imports } from './imports';
export type {
  ConfirmResult,
  ImportBlocker,
  ImportDay,
  ImportHistoryItem,
  ImportIssue,
  ImportRow,
  ImportState,
  ImportSummary,
  RowAction,
  Severity,
  UnmappedConsultant,
} from './imports';
export { reports } from './reports';
export type {
  BudgetResponse,
  ConsultantsResponse,
  ProductsResponse,
  ReportScope,
  SummaryResponse,
  WeekListItem,
} from './reports';
export { audit } from './audit';
export type { AuditEntry, AuditFilter } from './audit';
export { catalogue } from './catalogue';
export type { Employer, Product } from './catalogue';
export { detailOf, isStale } from './errors';
export { mfa, mfaPending } from './mfa';
export { ID_REVEAL_LIMIT, REAUTHENTICATION_REQUIRED, idReveal } from './idReveal';
export type { MfaEnabled, MfaSetup } from './mfa';

export const features = {
  import: true,
  removeClient: true,
  reportExport: !useMock,
  correctIdNumber: true,
};

export type {
  AssignmentChangeType,
  CaseDetail,
  CaseListItem,
  CaseNote,
  CaseStatus,
  ChangePasswordRequest,
  DashboardSummary,
  AssignmentEntry,
  ClientDetail,
  ClientFilter,
  ClientSummary,
  ClientUpdate,
  ConsultantRef,
  ConsultantSummary,
  ConsultantStatus,
  MoveRequest,
  MoveResult,
  MfaStatus,
  NewClient,
  PageResponse,
  Role,
  Session,
} from './types';
