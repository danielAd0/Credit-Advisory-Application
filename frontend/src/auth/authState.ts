import { createContext } from 'react';
import type { Session } from '../api';

export interface AuthState {
  session: Session | null;
  signIn: (email: string, password: string) => Promise<void>;
  signOut: () => Promise<void>;
}

/** Kept in its own file so AuthContext.tsx only exports a component (Vite fast refresh). */
export const AuthContext = createContext<AuthState | null>(null);
