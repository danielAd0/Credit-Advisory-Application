import { useContext } from 'react';
import type { Role, Session } from '../api';
import { AuthContext, type AuthState } from './authState';

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
}

/** The signed-in session. Only use inside pages guarded by RequireAuth. */
export function useSession(): Session {
  const { session } = useAuth();
  if (!session) throw new Error('No active session');
  return session;
}

/** True if the session has at least one of the given roles. */
export function hasAnyRole(session: Session, roles: Role[]): boolean {
  return roles.some((role) => session.roles.includes(role));
}
