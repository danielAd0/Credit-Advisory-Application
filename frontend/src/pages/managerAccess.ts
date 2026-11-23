import type { UserAccount } from '../api';

export function accessLabel(u: UserAccount): string {
  return u.roles.includes('ADMIN') ? 'Manager + administrator' : 'Manager';
}
