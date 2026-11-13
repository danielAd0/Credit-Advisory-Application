import { api, ApiError } from './http';
import { delay } from './mock';

export interface Target {
  id: number;
  /** yyyy-mm-dd: the first day this target applies. */
  effectiveFrom: string;
  dailyTarget: number;
  /** null only on very old rows. */
  weeklyTarget: number | null;
  /** null for the seeded row. */
  setByEmail: string | null;
  createdAt: string;
}

export interface TargetsResponse {
  today: string;
  current: Target | null;
  /** Newest effective date first, including targets that start in the future. */
  history: Target[];
}

export interface NewTarget {
  effectiveFrom: string;
  dailyTarget: number;
  weeklyTarget: number;
}

const real = {
  list: () => api<TargetsResponse>('/api/targets'),
  set: (t: NewTarget) => api<Target>('/api/targets', { method: 'POST', body: JSON.stringify(t) }),
};

// ---------- mock ----------

const pad = (n: number) => String(n).padStart(2, '0');
const todayText = () => {
  const d = new Date();
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
};

// Like a fresh database: no targets until a manager saves some (V25).
let rows: Target[] = [];

/** The target row in force on a yyyy-mm-dd date, as the reports look it up. */
export function mockTargetOn(date: string): Target | null {
  return (
    [...rows]
      .sort((a, b) => b.effectiveFrom.localeCompare(a.effectiveFrom))
      .find((t) => t.effectiveFrom <= date) ?? null
  );
}

const mock = {
  async list(): Promise<TargetsResponse> {
    await delay();
    const today = todayText();
    const history = [...rows].sort((a, b) => b.effectiveFrom.localeCompare(a.effectiveFrom));
    return { today, current: history.find((t) => t.effectiveFrom <= today) ?? null, history };
  },
  async set(t: NewTarget): Promise<Target> {
    await delay();
    const first = rows.map((r) => r.effectiveFrom).sort()[0];
    if (t.effectiveFrom < todayText() && first !== undefined && t.effectiveFrom >= first)
      throw new ApiError(422, 'Date in the past', {
        type: 'urn:credit-advisory:target:past-date',
        detail:
          'A target already applies to that date. A change must start today or later, so past weeks ' +
          `keep the target they had. Past dates can only be given a target before ${first}, when no target was set yet.`,
      });
    const saved: Target = {
      id: Math.max(0, ...rows.map((r) => r.id)) + 1,
      ...t,
      setByEmail: 'manager@mock.local',
      createdAt: new Date().toISOString(),
    };
    rows = [...rows.filter((r) => r.effectiveFrom !== t.effectiveFrom), saved];
    return saved;
  },
};

export const targets = import.meta.env.VITE_USE_MOCK === 'true' ? mock : real;
