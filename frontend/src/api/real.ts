import { api, fetchSession, signIn, signOut } from './http';
import type { ClientSummary, ConsultantSummary, PageResponse } from './types';

export const getSession = fetchSession;
export const login = signIn;
export const logout = signOut;

/** GET /api/clients?search= (contract section 4). Returns the first page's items. */
export const listClients = (search: string) =>
  api<PageResponse<ClientSummary>>(
    `/api/clients?search=${encodeURIComponent(search)}&size=100`,
  ).then((page) => page.items);

/** GET /api/consultants (contract section 3). Returns the first page's items. */
export const listConsultants = () =>
  api<PageResponse<ConsultantSummary>>('/api/consultants?size=100').then((page) => page.items);
