import type { ClientSummary } from './types';

export const clientName = (c: Pick<ClientSummary, 'firstName' | 'lastName'>) =>
  `${c.firstName} ${c.lastName}`;
