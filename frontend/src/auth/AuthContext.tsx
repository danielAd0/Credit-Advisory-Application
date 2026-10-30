import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { data, type Session } from '../api';
import { AuthContext } from './authState';

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

  const signIn = useCallback(async (email: string, password: string) => {
    const s = await data.login(email, password);
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

  if (status === 'error') {
    return <p className="center-msg">We could not reach the server. Please refresh the page.</p>;
  }
  if (status === 'loading') return <p className="center-msg">Loading...</p>;
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
