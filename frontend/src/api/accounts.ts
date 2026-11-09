import { api } from './http';
import * as admin from './admin';
import * as mockAccounts from './mockAccounts';
import type { ConsultantStatus, PageResponse } from './types';

export type { Consultant, ConsultantAccount, UserAccount } from './admin';

const useMock = import.meta.env.VITE_USE_MOCK === 'true';

async function findLogin(consultantId: number): Promise<admin.UserAccount | null> {
  const size = 100;
  for (let page = 0; page < 20; page++) {
    const res = await admin.listUsers({ page, size });
    const hit = res.items.find((u) => u.consultant?.id === consultantId);
    if (hit) return hit;
    if (page + 1 >= res.totalPages) return null;
  }

  return null;
}

const real = {
  listConsultants: (
    params: {
      search?: string;
      status?: ConsultantStatus;
      page?: number;
      size?: number;
    } = {},
  ): Promise<PageResponse<admin.Consultant>> => admin.listConsultants(params),
  createConsultantAccount: admin.createConsultantAccount,
  changeConsultantStatus: admin.changeConsultantStatus,
  findLogin,
  resetPassword: admin.resetPassword,
  unlockUser: (userId: number) =>
    api<admin.UserAccount>(`/api/users/${userId}/unlock`, { method: 'POST' }),
};

export const accounts = useMock ? mockAccounts : real;
