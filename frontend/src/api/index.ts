import * as mock from './mock';
import * as real from './real';

const useMock = import.meta.env.VITE_USE_MOCK === 'true';

export const data = useMock ? mock : real;
export { ApiError } from './http';
export { detailOf, isStale } from './errors';

/**
 * Screens the backend does not support yet. They work against the mock data so the design can be
 * reviewed, and switch on for real as soon as the backend has the endpoint (flip the flag to true).
 */
export const features = {
  /** Excel import: no endpoint in docs/api-auth-consultants-clients.md yet. */
  import: useMock,
  /** Removing (archiving) a client: no endpoint yet. */
  removeClient: useMock,
  /** Correcting an ID number: the business has not decided who may do it (SRS 15). */
  correctIdNumber: useMock,
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
  ImportIssue,
  ImportIssueKind,
  ImportPreview,
  ImportResult,
  MoveRequest,
  MoveResult,
  NewClient,
  PageResponse,
  Role,
  Session,
} from './types';
