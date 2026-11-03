import { createContext } from 'react';
import type { Session } from '../api';

export interface AuthState {
  session: Session | null;
  signIn: (email: string, password: string) => Promise<void>;
  signOut: () => Promise<void>;
  /** Asks the server for the current session again, e.g. after the password was changed. */
  refreshSession: () => Promise<void>;
}

/** Kept in its own file so AuthContext.tsx only exports a component (Vite fast refresh). */
export const AuthContext = createContext<AuthState | null>(null);
