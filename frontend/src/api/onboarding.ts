import { ApiError, api } from './http';
import { REAUTHENTICATION_REQUIRED } from './idReveal';
import * as mock from './mock';
import type { PageResponse } from './types';

/** Consultant logins, made and emailed in bulk (ADMIN only, docs/consultant-onboarding.md). */

export type InviteStatus = 'PENDING' | 'QUEUED' | 'SENDING' | 'SENT' | 'FAILED';

/** The report's filter: a status, or EXPIRED (sent, unused, past its time limit). */
export type InviteFilter = InviteStatus | 'EXPIRED' | '';

export interface OnboardingSummary {
  consultants: number;
  activeConsultants: number;
  withEmail: number;
  withLogin: number;
  /** Active, with an email, no login yet: "Generate temporary passwords" makes theirs. */
  eligibleForLogin: number;
  /** Active, no login, and cannot get one yet (see blocked()). */
  needAttention: number;
  invites: Record<InviteStatus, number>;
  /** False until IT has set up Microsoft 365 email. */
  mailConfigured: boolean;
  mailSender: string | null;
  loginUrl: string | null;
  /** Emails sent whose temporary password expired unused. */
  expiredLogins: number;
  /** Expired or failed logins that Regenerate gives a new password. */
  canRegenerate: number;
  /** How long an emailed temporary password works. */
  passwordDays: number;
}

export interface GenerateResult {
  created: number;
  remaining: number;
  skipped: {
    consultantId: number;
    consultantCode: string | null;
    fullName: string | null;
    reason: string;
  }[];
}

export interface Invite {
  id: number;
  consultantId: number;
  consultantCode: string;
  consultantName: string;
  userId: number;
  email: string;
  status: InviteStatus;
  attempts: number;
  lastError: string | null;
  generatedAt: string;
  queuedAt: string | null;
  lastAttemptAt: string | null;
  nextAttemptAt: string | null;
  sentAt: string | null;
  /** When the emailed password stops working (null: not sent yet, or already changed). */
  passwordExpiresAt: string | null;
  /** Sent, never used, past its time limit: regenerate it. */
  expired: boolean;
  /** The consultant has chosen their own password. */
  signedIn: boolean;
}

export interface BlockedConsultant {
  consultantId: number;
  consultantCode: string;
  fullName: string;
  email: string | null;
  reason: 'NO_EMAIL' | 'EMAIL_USED_BY_ANOTHER_LOGIN';
}

const post = <T>(path: string, body: unknown) =>
  api<T>(path, { method: 'POST', body: JSON.stringify(body) });

const real = {
  summary: () => api<OnboardingSummary>('/api/onboarding/summary'),
  blocked: (page = 0, size = 50) =>
    api<PageResponse<BlockedConsultant>>(`/api/onboarding/blocked?page=${page}&size=${size}`),
  /** Up to 25 logins per call. 403 REAUTHENTICATION_REQUIRED: ask for the password first. */
  generate: () => post<GenerateResult>('/api/onboarding/logins', {}),
  invites: (status: InviteFilter, page = 0, size = 50) =>
    api<PageResponse<Invite>>(
      `/api/onboarding/invites?page=${page}&size=${size}${status ? `&status=${status}` : ''}`,
    ),
  send: (inviteIds?: number[]) =>
    post<{ queued: number }>('/api/onboarding/invites/send', inviteIds ? { inviteIds } : {}),
  retry: (inviteIds?: number[]) =>
    post<{ queued: number }>('/api/onboarding/invites/retry', inviteIds ? { inviteIds } : {}),
  /** New passwords for expired or failed logins, up to 25 per call; then send them. */
  regenerate: (inviteIds?: number[]) =>
    post<GenerateResult>('/api/onboarding/invites/regenerate', inviteIds ? { inviteIds } : {}),
  setConsultantEmail: (consultantId: number, email: string) =>
    api<{ consultantId: number; email: string }>(
      `/api/onboarding/consultants/${consultantId}/email`,
      {
        method: 'PUT',
        body: JSON.stringify({ email }),
      },
    ),
};

// ---------- mock (VITE_USE_MOCK=true): a handful of made-up consultants ----------

interface MockConsultant {
  id: number;
  code: string;
  name: string;
  email: string | null;
  hasLogin: boolean;
}

const mockConsultants: MockConsultant[] = [
  { id: 1, code: 'C0001', name: 'Thandi Xundu', email: 'thandi@example.test', hasLogin: true },
  { id: 2, code: 'C0002', name: 'Sipho Dlamini', email: 'sipho@example.test', hasLogin: false },
  { id: 3, code: 'C0003', name: 'Naledi Mokoena', email: 'naledi@example.test', hasLogin: false },
  { id: 4, code: 'C0004', name: 'Lerato Molefe', email: null, hasLogin: false },
];
let mockInvites: Invite[] = [];
let mockConfirmedUntil = 0;

function requireMockPassword() {
  if (Date.now() > mockConfirmedUntil) {
    const detail = 'Enter your password again to continue.';
    throw new ApiError(403, detail, { type: REAUTHENTICATION_REQUIRED, detail });
  }
}

/** The mock "sends" queued emails a second after they are queued. */
function mockDeliverSoon() {
  setTimeout(() => {
    mockInvites = mockInvites.map(
      (i): Invite =>
        i.status === 'QUEUED'
          ? {
              ...i,
              status: 'SENT',
              attempts: i.attempts + 1,
              sentAt: new Date().toISOString(),
              passwordExpiresAt: new Date(Date.now() + 7 * 864e5).toISOString(),
            }
          : i,
    );
  }, 1000);
}

const fake: typeof real = {
  summary: async () => {
    await mock.delay();
    const counts: Record<InviteStatus, number> = {
      PENDING: 0,
      QUEUED: 0,
      SENDING: 0,
      SENT: 0,
      FAILED: 0,
    };
    mockInvites.forEach((i) => counts[i.status]++);
    return {
      consultants: mockConsultants.length,
      activeConsultants: mockConsultants.length,
      withEmail: mockConsultants.filter((c) => c.email).length,
      withLogin: mockConsultants.filter((c) => c.hasLogin).length,
      eligibleForLogin: mockConsultants.filter((c) => c.email && !c.hasLogin).length,
      needAttention: mockConsultants.filter((c) => !c.email && !c.hasLogin).length,
      invites: counts,
      mailConfigured: true,
      mailSender: 'no-reply@example.test',
      loginUrl: 'http://localhost:5173',
      expiredLogins: mockInvites.filter((i) => i.expired).length,
      canRegenerate: mockInvites.filter((i) => i.expired || i.status === 'FAILED').length,
      passwordDays: 7,
    };
  },
  blocked: async (page = 0, size = 50) => {
    await mock.delay();
    const items = mockConsultants
      .filter((c) => !c.email && !c.hasLogin)
      .map((c) => ({
        consultantId: c.id,
        consultantCode: c.code,
        fullName: c.name,
        email: null,
        reason: 'NO_EMAIL' as const,
      }));
    return { items, page, size, totalItems: items.length, totalPages: 1 };
  },
  generate: async () => {
    await mock.delay(600);
    requireMockPassword();
    let created = 0;
    for (const c of mockConsultants) {
      if (c.email && !c.hasLogin) {
        c.hasLogin = true;
        created++;
        mockInvites.unshift({
          id: mockInvites.length + 1,
          consultantId: c.id,
          consultantCode: c.code,
          consultantName: c.name,
          userId: 100 + c.id,
          email: c.email,
          status: 'PENDING',
          attempts: 0,
          lastError: null,
          generatedAt: new Date().toISOString(),
          queuedAt: null,
          lastAttemptAt: null,
          nextAttemptAt: null,
          sentAt: null,
          passwordExpiresAt: null,
          expired: false,
          signedIn: false,
        });
      }
    }
    return { created, remaining: 0, skipped: [] };
  },
  invites: async (status, page = 0, size = 50) => {
    await mock.delay();
    const items = mockInvites.filter((i) =>
      !status ? true : status === 'EXPIRED' ? i.expired : i.status === status,
    );
    return { items, page, size, totalItems: items.length, totalPages: 1 };
  },
  send: async () => {
    await mock.delay();
    requireMockPassword();
    let queued = 0;
    mockInvites = mockInvites.map((i): Invite => {
      if (i.status !== 'PENDING') return i;
      queued++;
      return { ...i, status: 'QUEUED', queuedAt: new Date().toISOString() };
    });
    mockDeliverSoon();
    return { queued };
  },
  retry: async () => {
    await mock.delay();
    requireMockPassword();
    let queued = 0;
    mockInvites = mockInvites.map((i): Invite => {
      if (i.status !== 'FAILED') return i;
      queued++;
      return { ...i, status: 'QUEUED', attempts: 0, lastError: null };
    });
    mockDeliverSoon();
    return { queued };
  },
  regenerate: async () => {
    await mock.delay(600);
    requireMockPassword();
    let created = 0;
    mockInvites = mockInvites.map((i): Invite => {
      if (!i.expired && i.status !== 'FAILED') return i;
      created++;
      return {
        ...i,
        status: 'PENDING',
        attempts: 0,
        lastError: null,
        sentAt: null,
        passwordExpiresAt: null,
        expired: false,
      };
    });
    return { created, remaining: 0, skipped: [] };
  },
  setConsultantEmail: async (consultantId, email) => {
    await mock.delay();
    const c = mockConsultants.find((x) => x.id === consultantId);
    if (!c) throw new ApiError(404, 'Consultant not found.');
    c.email = email.trim().toLowerCase();
    return { consultantId, email: c.email };
  },
};

/** The mock's password check: confirmPassword in idReveal's mock does not reach here. */
export function mockConfirmPassword() {
  mockConfirmedUntil = Date.now() + 5 * 60 * 1000;
}

const useMock = import.meta.env.VITE_USE_MOCK === 'true';

export const onboarding: typeof real = useMock ? fake : real;
