import { api } from './http';
import * as admin from './admin';
import * as mockUsers from './mockUsers';

/** Staff logins (managers and administrators), managed by an ADMIN. */
const real = {
  listUsers: admin.listUsers,
  createUser: admin.createUser,
  changeRoles: admin.changeUserRoles,
  changeConsultant: admin.changeUserConsultant,
  setEnabled: admin.setUserEnabled,
  resetPassword: admin.resetPassword,
  unlock: (userId: number) =>
    api<admin.UserAccount>(`/api/users/${userId}/unlock`, { method: 'POST' }),
};

const useMock = import.meta.env.VITE_USE_MOCK === 'true';

export const users: typeof real = useMock ? mockUsers : real;
