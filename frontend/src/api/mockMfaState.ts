import type { MfaStatus } from './types';

/**
 * Mock mode only. With VITE_MOCK_MFA=true the mock asks for the second sign-in step like the real
 * server does; without it the mock skips it (like app.mfa.required=false on the backend).
 * Kept apart from mock.ts and mfa.ts so neither has to import the other.
 */
const ON = import.meta.env.VITE_MOCK_MFA === 'true';
const PENDING = 'mockMfaPending';
const ENROLLED = 'mockMfaEnrolled';

export function mockMfaStatus(): MfaStatus {
  if (!ON) return 'NOT_REQUIRED';
  if (sessionStorage.getItem(PENDING) !== 'yes') return 'VERIFIED';
  return sessionStorage.getItem(ENROLLED) === 'yes' ? 'CODE_REQUIRED' : 'SETUP_REQUIRED';
}

/** After the password: the second step is still to do. */
export function mockMfaSignedIn(): void {
  if (ON) sessionStorage.setItem(PENDING, 'yes');
}

export function mockMfaSignedOut(): void {
  sessionStorage.removeItem(PENDING);
}

export function mockMfaVerified(): void {
  sessionStorage.removeItem(PENDING);
}

export function mockMfaEnrol(): void {
  sessionStorage.setItem(ENROLLED, 'yes');
}
