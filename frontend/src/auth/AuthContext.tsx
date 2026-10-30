import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { data, type Session } from '../api';

interface AuthState {
  session: Session | null;
  signIn: (username: string, password: string, remember: boolean) => Promise<void>;
  signOut: () => Promise<void>;
}
const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null);
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading');

  useEffect(() => {
    let live = true;
    data
      .getSession()
      .then((s) => {
        if (live) {
          setSession(s);
          setStatus('ready');
        }
      })
      .catch(() => {
        if (live) setStatus('error');
      });
    return () => {
      live = false;
    };
  }, []);

  const signIn = useCallback(async (username: string, password: string, remember: boolean) => {
    await data.login(username, password, remember);
    const s = await data.getSession();
    if (!s) throw new Error('Signed in but no session came back');
    setSession(s);
  }, []);

  const signOut = useCallback(async () => {
    try {
      await data.logout();
    } finally {

      window.location.assign('/login?logout');
    }
  }, []);

  const value = useMemo(() => ({ session, signIn, signOut }), [session, signIn, signOut]);

  if (status === 'error') return <p className="center-msg">We could not reach the server. Please refresh the page.</p>;
  if (status === 'loading') return <p className="center-msg">Loading...</p>;
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
}

export function useSession(): Session {
  const { session } = useAuth();
  if (!session) throw new Error('No active session');
  return session;
}
