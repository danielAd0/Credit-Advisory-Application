import type { ClientFields, ClientSummary, PaymentMethod } from '../api';
import { phoneForDisplay as local } from '../api/names';

/**
 * Values, checks and payload for the client fields the Add and Edit client
 * forms share (ClientDetailsFields.tsx renders them).
 */

export interface DetailsValues {
  phone: string;
  workPhone: string;
  homePhone: string;
  /** The chosen id as text, or empty for none. */
  employerId: string;
  productQuotationId: string;
  paymentMethod: '' | PaymentMethod;
  fileNumber: string;
  fileId: string;
}

export type DetailsErrors = Partial<Record<keyof DetailsValues, string>>;

export const EMPTY_DETAILS: DetailsValues = {
  phone: '',
  workPhone: '',
  homePhone: '',
  employerId: '',
  productQuotationId: '',
  paymentMethod: '',
  fileNumber: '',
  fileId: '',
};

/** File number and File ID: digits only, at most 20 (the server checks the same). */
export const FILE_REFERENCE_MAX = 20;

export function detailsFrom(c: ClientSummary): DetailsValues {
  return {
    phone: local(c.phone),
    workPhone: local(c.workPhone),
    homePhone: local(c.homePhone),
    employerId: c.employer ? String(c.employer.id) : '',
    productQuotationId: c.productQuotation ? String(c.productQuotation.id) : '',
    paymentMethod: c.paymentMethod ?? '',
    fileNumber: c.fileNumber ?? '',
    fileId: c.fileId ?? '',
  };
}

/** Same rules as the server (PhoneNumbers.java), so a bad number is caught before saving. */
function phoneError(raw: string): string | undefined {
  const compact = raw.replace(/[\s.()-]/g, '');
  if (!compact) return undefined;
  const ok =
    /^0\d{9}$/.test(compact) ||
    /^27\d{9}$/.test(compact) ||
    /^(\+|00)27\d{9}$/.test(compact) ||
    (/^(\+|00)[1-9]\d{7,14}$/.test(compact) && !/^(\+|00)27/.test(compact));
  return ok
    ? undefined
    : 'Use a South African number such as 082 123 4567, or + and the country code.';
}

export function validateDetails(v: DetailsValues): DetailsErrors {
  const e: DetailsErrors = {};
  for (const key of ['phone', 'workPhone', 'homePhone'] as const) {
    const problem = phoneError(v[key]);
    if (problem) e[key] = problem;
  }
  return e;
}

/** The shared part of what is sent to the server. Empty text means none. */
export function detailsPayload(
  v: DetailsValues,
): Omit<ClientFields, 'firstName' | 'lastName' | 'email'> {
  return {
    phone: v.phone.trim(),
    workPhone: v.workPhone.trim(),
    homePhone: v.homePhone.trim(),
    employerId: v.employerId ? Number(v.employerId) : null,
    productQuotationId: v.productQuotationId ? Number(v.productQuotationId) : null,
    paymentMethod: v.paymentMethod || null,
    fileNumber: v.fileNumber,
    fileId: v.fileId,
  };
}
