import type { Session } from './types';

export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

/** Reads a cookie set by the backend (the CSRF cookie is deliberately readable). */
function readCookie(name: string): string | null {
  const match = document.cookie.split('; ').find((c) => c.startsWith(`${name}=`));
  return match ? decodeURIComponent(match.slice(name.length + 1)) : null;
}

/** Makes sure the XSRF-TOKEN cookie exists and returns its value (contract section 1). */
async function csrfToken(): Promise<string> {
  let token = readCookie('XSRF-TOKEN');
  if (!token) {
    await fetch('/api/auth/csrf', { credentials: 'include' });
    token = readCookie('XSRF-TOKEN');
  }
  if (!token) throw new ApiError(0, 'Could not start a secure session.');
  return token;
}

export async function fetchSession(): Promise<Session | null> {
  const res = await fetch('/api/auth/me', {
    credentials: 'include',
    headers: { Accept: 'application/json' },
  });
  if (res.status === 401) return null;
  if (!res.ok) throw new ApiError(res.status, 'Could not load your session.');
  return (await res.json()) as Session;
}

export async function signIn(email: string, password: string): Promise<Session> {
  const res = await fetch('/api/auth/login', {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
      'X-XSRF-TOKEN': await csrfToken(),
    },
    body: JSON.stringify({ email, password }),
  });
  if (res.status === 401) {
    throw new ApiError(401, 'Sign-in failed. Check your details or try again later.');
  }
  if (!res.ok) throw new ApiError(res.status, 'Sign-in failed. Please try again.');
  return (await res.json()) as Session;
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = (init.method ?? 'GET').toUpperCase();
  const headers = new Headers(init.headers);
  headers.set('Accept', 'application/json');
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
  if (method !== 'GET' && method !== 'HEAD') headers.set('X-XSRF-TOKEN', await csrfToken());

  const res = await fetch(path, { ...init, method, headers, credentials: 'include' });

  if (res.status === 401) {
    window.location.assign('/login');
    throw new ApiError(401, 'Please sign in again.');
  }
  if (!res.ok) {
    throw new ApiError(
      res.status,
      res.status === 403
        ? 'You do not have permission to do that.'
        : 'Something went wrong. Please try again.',
    );
  }
  return res.status === 204 ? (undefined as T) : ((await res.json()) as T);
}

export async function signOut(): Promise<void> {
  const token = readCookie('XSRF-TOKEN');
  await fetch('/api/auth/logout', {
    method: 'POST',
    credentials: 'include',
    headers: token ? { 'X-XSRF-TOKEN': token } : {},
  });
}
