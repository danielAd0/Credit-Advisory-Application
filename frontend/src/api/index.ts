import * as mock from './mock';
import * as real from './real';

export const data = import.meta.env.VITE_USE_MOCK === 'true' ? mock : real;
export { ApiError } from './http';
export type {
  ClientSummary,
  ConsultantSummary,
  ConsultantStatus,
  PageResponse,
  Role,
  Session,
} from './types';
