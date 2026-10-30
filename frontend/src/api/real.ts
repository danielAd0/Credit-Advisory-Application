import { api , fetchSession , signIn , signOut} from './http';
import type {ClientSummary ,ConsultantSummary } from './types';


export const getSession = fetchSession;
export const login = signIn;
export const logout = signOut;

export const listClients = (query : string) => api<ClientSummary[]>(`/api/clients?q=${encodeURIComponent(query)}`);
export const listConsultants = () => api<ConsultantSummary[]>('/api/consultants');
