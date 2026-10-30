import type {Session} from './types';


let csrf: Session['csrf'] | null = null;

export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

export async function fetchSession() : Promise<Session | null> {
  const res = await fetch('/api/auth/me', {
    credentials: 'include',
    headers: { Accept: 'application/json' },
  });

  if(res.status === 401) return null;
  if(!res.ok) throw new ApiError(res.status, 'Could not load your session.');

  const session = (await res.json()) as Session;
  csrf = session.csrf;
  return session;

}

export async function signIn(username : string , password : string , remember : boolean) : Promise<void>{
    const t = await fetch('/api/auth/csrf', { credentials: 'include', headers: { Accept: 'application/json' } });
    if(!t.ok) throw new ApiError(t.status, 'Could not start sign-in.');
    const c = (await t.json()) as {parameterName : string; token : string};
    const body = new URLSearchParams({username , password , [c.parameterName] : c.token});
    if(remember) body.set('remember-me' , 'true');

    const res = await fetch('/api/auth/login', {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,});

    if(!res.ok) throw new ApiError(res.status , 'Sign-in failed');
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = (init.method ?? 'GET').toUpperCase();
  const headers = new Headers(init.headers);
  headers.set('Accept', 'application/json');

  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
  if (method !== 'GET' && method !== 'HEAD' && csrf) headers.set(csrf.headerName, csrf.token);

  const res = await fetch(path, { ...init, method, headers, credentials: 'include' });

  if (res.status === 401) {
    window.location.assign('/login');
    throw new ApiError(401, 'Please sign in again.');
  }

  if (!res.ok) {
    throw new ApiError(
      res.status,
      res.status === 403 ? 'You do not have permission to do that.' : 'Something went wrong. Please try again.',
    );
  }

  return res.status === 204 ? (undefined as T) : ((await res.json()) as T);
}

export async function signOut() : Promise<void> {
    try{
      await fetch('/logout' , { method : 'POST' , credentials : 'include' , headers : csrf ? { [csrf.headerName] : csrf.token} : {}});
    }

    finally{
      csrf = null;
    }
}
