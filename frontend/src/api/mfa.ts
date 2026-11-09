import { ApiError, api } from './http';
import * as mock from './mock';
import { mockMfaEnrol, mockMfaVerified } from './mockMfaState';
import type { Session } from './types';

/** Answer of POST /api/auth/mfa/setup. */
export interface MfaSetup {
  /** The secret in base32, for typing into the app when the QR code cannot be scanned. */
  secret: string;
  /** What the QR code must contain. */
  otpauthUri: string;
}

/** Answer of POST /api/auth/mfa/enable. The recovery codes are shown ONCE and never again. */
export interface MfaEnabled {
  recoveryCodes: string[];
  account: Session;
}

const useMock = import.meta.env.VITE_USE_MOCK === 'true';

const post = <T>(path: string, body: unknown) =>
  api<T>(path, { method: 'POST', body: JSON.stringify(body) });

/** The second sign-in step (contract section 2, "Two-step sign-in"). */
const real = {
  /** First time: a new secret for the authenticator app. */
  setup: () => post<MfaSetup>('/api/auth/mfa/setup', {}),
  /** First time: the code the app now shows. Returns the recovery codes. */
  enable: (code: string) => post<MfaEnabled>('/api/auth/mfa/enable', { code }),
  /** Every sign-in: the code the app shows now. */
  verify: (code: string) => post<Session>('/api/auth/mfa/verify', { code }),
  /** Lost phone: one of the recovery codes instead. */
  recover: (recoveryCode: string) => post<Session>('/api/auth/mfa/recovery', { recoveryCode }),
  /** ADMIN: remove a user's authenticator; they set up a new one at their next sign-in. */
  reset: (userId: number) => api<void>(`/api/users/${userId}/mfa-reset`, { method: 'POST' }),
};

// ---------- mock: "111111" is a wrong code; any other 6 digits work ----------

const WRONG = new ApiError(400, 'The code is not correct.', {
  type: 'urn:credit-advisory:auth:wrong-code',
  detail: 'The code is not correct. Enter the code your authenticator app shows now.',
});

async function mockAccount(): Promise<Session> {
  const session = await mock.getSession();
  if (!session) throw new ApiError(401, 'Please sign in again.');
  return session;
}

const fake = {
  setup: async (): Promise<MfaSetup> => {
    await mock.delay(300);
    const secret = 'JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP';
    return {
      secret,
      otpauthUri: `otpauth://totp/Credit%20Advisory:demo?secret=${secret}&issuer=Credit%20Advisory`,
    };
  },
  enable: async (code: string): Promise<MfaEnabled> => {
    await mock.delay(400);
    if (code === '111111') throw WRONG;
    mockMfaEnrol();
    mockMfaVerified();
    const recoveryCodes = [
      'K7QM2-XW4PD',
      'R3HNA-9TBVE',
      'P6WZC-2MKJF',
      'D8FGX-YQ4RS',
      'N5TJB-HW7CA',
      'X2VEM-QK9PG',
      'B4RYS-6DNZT',
      'G9KCF-WM3HX',
      'T7PAQ-5JVRB',
      'Z3NDW-8EYKM',
    ];
    return { recoveryCodes, account: await mockAccount() };
  },
  verify: async (code: string): Promise<Session> => {
    await mock.delay(400);
    if (code === '111111') throw WRONG;
    mockMfaVerified();
    return mockAccount();
  },
  recover: async (recoveryCode: string): Promise<Session> => {
    await mock.delay(400);
    if (recoveryCode.replace(/[\s-]/g, '').length !== 10) throw WRONG;
    mockMfaVerified();
    return mockAccount();
  },
  reset: async (): Promise<void> => {
    await mock.delay(400);
  },
};

export const mfa = useMock ? fake : real;

/** True while this session must still do the second step. */
export const mfaPending = (session: Session) =>
  session.mfa === 'SETUP_REQUIRED' || session.mfa === 'CODE_REQUIRED';
