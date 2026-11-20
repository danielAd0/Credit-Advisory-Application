import * as mock from './mock';
import * as real from './real';

const useMock = import.meta.env.VITE_USE_MOCK === 'true';

export const data = useMock ? mock : real;

export { ApiError } from './http';
export { accounts } from './accounts';
export { users } from './users';
export type { Consultant, ConsultantAccount, UserAccount } from './accounts';
export { imports } from './imports';
export { clientListImports } from './clientListImport';
export type {
  ClientListAction,
  ClientListBlocker,
  ClientListConfirmResult,
  ClientListHistoryItem,
  ClientListIssue,
  ClientListRow,
  ClientListSeverity,
  ClientListState,
  ClientListSummary,
} from './clientListImport';

export type {
  ConfirmResult,
  ConsultantAttention,
  ConsultantFound,
  ConsultantList,
  ConsultantOutcome,
  ImportBlocker,
  ImportDay,
  ImportHistoryItem,
  ImportIssue,
  ImportResult,
  ImportRow,
  ImportState,
  ImportSummary,
  RowAction,
  Severity,
} from './imports';

export { reports } from './reports';
export { workbookCheck } from './workbookCheck';
export type {
  WorkbookCheckDefect,
  WorkbookCheckFigure,
  WorkbookCheckFilter,
  WorkbookCheckSection,
  WorkbookCheckStatus,
  WorkbookCheckSummary,
} from './workbookCheck';

export type {
  BudgetResponse,
  ConsultantsResponse,
  MonthListItem,
  MonthsResponse,
  ProductsResponse,
  RangeBudgetResponse,
  RangeConsultantsResponse,
  RangeDay,
  RangeProductsResponse,
  RangeSummaryResponse,
  RangeWeekPart,
  ReportScope,
  SummaryResponse,
  WeekListItem,
} from './reports';

export { transactions } from './transactions';

export type {
  CaptureLists,
  EditSale,
  NewTransaction,
  Option,
  SaleDetail,
  SaleFilter,
  SaleFlag,
  SaleRow,
  SalesPage,
  SavedLine,
  SavedTransaction,
} from './transactions';

export { targets } from './targets';
export type { NewTarget, Target, TargetsResponse } from './targets';
export { audit } from './audit';
export type { AuditEntry, AuditFilter } from './audit';
export { catalogue } from './catalogue';
export type { Employer, MergeEmployerResult, Product, ProductQuotation } from './catalogue';
export { detailOf, isStale } from './errors';
export { mfa, mfaPending } from './mfa';
export { ID_REVEAL_LIMIT, REAUTHENTICATION_REQUIRED, idReveal } from './idReveal';
export type { ExportFormat } from './idReveal';
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
  ClientFields,
  ClientFilter,
  ClientSummary,
  ClientUpdate,
  ConsultantRef,
  ConsultantSummary,
  ConsultantStatus,
  LatestSale,
  MoveRequest,
  MoveResult,
  MfaStatus,
  NamedRef,
  NewClient,
  PageResponse,
  PaymentMethod,
  Role,
  Session,
} from './types';
