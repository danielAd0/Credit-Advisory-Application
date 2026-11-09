import { ApiError, api } from './http';
import * as mock from './mock';
import type { ClientDetail } from './types';

/** The server's answer when the password was not re-entered in the last 5 minutes. */
export const REAUTHENTICATION_REQUIRED = 'urn:credit-advisory:auth:reauthentication-required';
/** The server's answer when this user has seen 20 full ID numbers this hour. */
export const ID_REVEAL_LIMIT = 'urn:credit-advisory:client:id-reveal-limit';

const useMock = import.meta.env.VITE_USE_MOCK === 'true';

/**
 * Showing a full ID number (contract section 4, GET /api/clients/{id}?include=idNumber):
 * only within 5 minutes of re-entering the password, at most 20 per hour, and every one is
 * written to the audit log. The number is never kept anywhere but the screen showing it.
 */
const real = {
  /** POST /api/auth/reauthenticate. A wrong password is a 400 auth:wrong-password. */
  confirmPassword: (password: string) =>
    api<{ confirmedUntil: string }>('/api/auth/reauthenticate', {
      method: 'POST',
      body: JSON.stringify({ password }),
    }),
  /** The full number. 403 REAUTHENTICATION_REQUIRED: ask for the password, then try again. */
  revealIdNumber: async (clientId: number): Promise<string> => {
    const client = await api<ClientDetail & { idNumber: string | null }>(
      `/api/clients/${clientId}?include=idNumber`,
    );
    if (!client.idNumber) throw new ApiError(500, 'The ID number was not returned.');
    return client.idNumber;
  },
};

// ---------- mock: the password "wrong" is wrong; the limit is 20 per page load ----------

let mockConfirmedUntil = 0;
let mockReveals = 0;

const fake = {
  confirmPassword: async (password: string) => {
    await mock.delay(400);
    if (password === 'wrong') {
      throw new ApiError(400, 'The password is not correct.', {
        type: 'urn:credit-advisory:auth:wrong-password',
        detail: 'The password is not correct.',
      });
    }
    mockConfirmedUntil = Date.now() + 5 * 60 * 1000;
    return { confirmedUntil: new Date(mockConfirmedUntil).toISOString() };
  },
  revealIdNumber: async (clientId: number): Promise<string> => {
    await mock.delay(300);
    if (Date.now() > mockConfirmedUntil) {
      throw new ApiError(403, 'Enter your password again to see a full ID number.', {
        type: REAUTHENTICATION_REQUIRED,
        detail: 'Enter your password again to see a full ID number.',
      });
    }
    if (++mockReveals > 20) {
      const detail =
        'You have viewed the maximum number of full ID numbers for this hour. Try again later.';
      throw new ApiError(429, detail, { type: ID_REVEAL_LIMIT, detail });
    }
    const client = await mock.getClient(clientId);
    // Not a real ID number: zeros where the mask hides digits.
    return client.idNumberMasked.replace(/\*/g, '0');
  },
};

export const idReveal = useMock ? fake : real;
