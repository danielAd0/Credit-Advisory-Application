import type { ClientSummary } from './types';

/** A stored phone the way people type it: +27821234567 -> 082 123 4567. Others unchanged. */
export function phoneForDisplay(phone: string | null): string {
  if (!phone) return '';
  const m = /^\+27(\d{2})(\d{3})(\d{4})$/.exec(phone);
  return m ? `0${m[1]} ${m[2]} ${m[3]}` : phone;
}

export const clientName = (c: Pick<ClientSummary, 'firstName' | 'lastName'>) =>
  `${c.firstName} ${c.lastName}`;
