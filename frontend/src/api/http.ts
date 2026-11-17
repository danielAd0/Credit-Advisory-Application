import type { Session } from './types';

export interface FieldError {
  field: string;
  message: string;
}

export interface Problem {
  type: string | null;
  detail: string | null;
  errors: FieldError[];
}

export const PASSWORD_CHANGE_REQUIRED = 'urn:credit-advisory:auth:password-change-required';
export const MFA_REQUIRED = 'urn:credit-advisory:auth:mfa-required';
export const STALE_VERSION = 'urn:credit-advisory:conflict:stale-version';

export class ApiError extends Error {
  status: number;
  type: string | null;
  detail: string | null;
  errors: FieldError[];
  retryAfter: number | null = null;
  constructor(status: number, message: string, problem: Partial<Problem> | null = null) {
    super(message);
    this.status = status;
    this.type = problem?.type ?? null;
    this.detail = problem?.detail ?? null;
    this.errors = problem?.errors ?? [];
  }
}

async function readProblem(res: Response): Promise<Problem | null> {
  try {
    const body: unknown = await res.json();
    if (typeof body !== 'object' || body === null) return null;
    const b = body as { type?: unknown; detail?: unknown; errors?: unknown };
    const errors = Array.isArray(b.errors)
      ? b.errors.flatMap((e: unknown) => {
          const f = e as { field?: unknown; message?: unknown };
          return typeof f.field === 'string' && typeof f.message === 'string'
            ? [{ field: f.field, message: f.message }]
            : [];
        })
      : [];
    return {
      type: typeof b.type === 'string' ? b.type : null,
      detail: typeof b.detail === 'string' ? b.detail : null,
      errors,
    };
  } catch {
    return null;
  }
}

function readCookie(name: string): string | null {
  const match = document.cookie.split('; ').find((c) => c.startsWith(`${name}=`));
  return match ? decodeURIComponent(match.slice(name.length + 1)) : null;
}

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
  if (res.status === 429) {
    throw new ApiError(
      429,
      'Too many failed sign-in attempts. Please wait a few minutes and try again.',
    );
  }
  if (!res.ok) throw new ApiError(res.status, 'Sign-in failed. Please try again.');
  return (await res.json()) as Session;
}

function retryAfterSeconds(res: Response): number | null {
  const value = res.headers.get('Retry-After');
  if (!value) return null;
  const seconds = Number(value);
  if (Number.isFinite(seconds)) return seconds > 0 ? seconds : null;
  const at = Date.parse(value);
  if (Number.isNaN(at)) return null;
  const wait = Math.ceil((at - Date.now()) / 1000);
  return wait > 0 ? wait : null;
}

async function send(path: string, init: RequestInit = {}): Promise<Response> {
  const method = (init.method ?? 'GET').toUpperCase();
  const headers = new Headers(init.headers);
  headers.set('Accept', 'application/json');

  if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type'))
    headers.set('Content-Type', 'application/json');
  if (method !== 'GET' && method !== 'HEAD') headers.set('X-XSRF-TOKEN', await csrfToken());

  const res = await fetch(path, { ...init, method, headers, credentials: 'include' });

  if (res.status === 401) {
    window.location.assign('/login');
    throw new ApiError(401, 'Please sign in again.');
  }
  if (!res.ok) {
    const problem = await readProblem(res);

    if (res.status === 403 && problem?.type === MFA_REQUIRED) {
      window.location.assign('/two-step');
      throw new ApiError(403, 'Enter the code from your authenticator app first.', problem);
    }

    if (res.status === 403 && problem?.type === PASSWORD_CHANGE_REQUIRED) {
      window.location.assign('/change-password');
      throw new ApiError(403, 'Choose your own password first.', problem);
    }
    const failure = new ApiError(
      res.status,
      problem?.detail ??
        (res.status === 403
          ? 'You do not have permission to do that.'
          : 'Something went wrong. Please try again.'),
      problem,
    );
    failure.retryAfter = retryAfterSeconds(res);
    throw failure;
  }
  return res;
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const res = await send(path, init);
  return res.status === 204 ? (undefined as T) : ((await res.json()) as T);
}

export async function apiFile(path: string, init: RequestInit = {}): Promise<Blob> {
  const res = await send(path, init);
  return res.blob();
}

export async function signOut(): Promise<void> {
  const token = readCookie('XSRF-TOKEN');
  await fetch('/api/auth/logout', {
    method: 'POST',
    credentials: 'include',
    headers: token ? { 'X-XSRF-TOKEN': token } : {},
  });
}
