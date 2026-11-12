import { ApiError, api } from './http';
import * as mock from './mock';
import { mockMfaEnrol, mockMfaVerified } from './mockMfaState';
import type { Session } from './types';

export interface MfaSetup {
  secret: string;
  otpauthUri: string;
}

export interface MfaEnabled {
  recoveryCodes: string[];
  account: Session;
}

const useMock = import.meta.env.VITE_USE_MOCK === 'true';

const post = <T>(path: string, body: unknown) =>
  api<T>(path, { method: 'POST', body: JSON.stringify(body) });

const real = {
  setup: () => post<MfaSetup>('/api/auth/mfa/setup', {}),
  enable: (code: string) => post<MfaEnabled>('/api/auth/mfa/enable', { code }),
  verify: (code: string) => post<Session>('/api/auth/mfa/verify', { code }),
  recover: (recoveryCode: string) => post<Session>('/api/auth/mfa/recovery', { recoveryCode }),
  change: () => post<MfaSetup>('/api/auth/mfa/change', {}),
  confirmChange: (code: string) => post<MfaEnabled>('/api/auth/mfa/change/confirm', { code }),
  reset: (userId: number) => api<void>(`/api/users/${userId}/mfa-reset`, { method: 'POST' }),
};

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
  change: async (): Promise<MfaSetup> => {
    await mock.delay(300);
    const secret = 'MFRGGZDFMZTWQ2LKNNWG23TPOBYXE43U';
    return {
      secret,
      otpauthUri: `otpauth://totp/Credit%20Advisory:demo?secret=${secret}&issuer=Credit%20Advisory`,
    };
  },
  confirmChange: async (code: string): Promise<MfaEnabled> => {
    await mock.delay(400);
    if (code === '111111') throw WRONG;
    const recoveryCodes = [
      'A2BCD-3EFGH',
      'J4KLM-5NPQR',
      'S6TUV-7WXYZ',
      'C8DEF-9GHJK',
      'M2NPQ-3RSTV',
      'W4XYZ-5ABCD',
      'E6FGH-7JKLM',
      'N8PQR-9STUV',
      'X2YZA-3BCDE',
      'F4GHJ-5KLMN',
    ];
    return { recoveryCodes, account: await mockAccount() };
  },
  reset: async (): Promise<void> => {
    await mock.delay(400);
  },
};

export const mfa = useMock ? fake : real;

export const mfaPending = (session: Session) =>
  session.mfa === 'SETUP_REQUIRED' || session.mfa === 'CODE_REQUIRED';
