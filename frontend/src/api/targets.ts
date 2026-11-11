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

let rows: Target[] = [
  {
    id: 1,
    effectiveFrom: '2026-01-01',
    dailyTarget: 120000,
    weeklyTarget: 600000,
    setByEmail: null,
    createdAt: '2026-01-01T00:00:00Z',
  },
];

const mock = {
  async list(): Promise<TargetsResponse> {
    await delay();
    const today = todayText();
    const history = [...rows].sort((a, b) => b.effectiveFrom.localeCompare(a.effectiveFrom));
    return { today, current: history.find((t) => t.effectiveFrom <= today) ?? null, history };
  },
  async set(t: NewTarget): Promise<Target> {
    await delay();
    if (t.effectiveFrom < todayText())
      throw new ApiError(422, 'Date in the past', {
        type: 'urn:credit-advisory:target:past-date',
        detail: 'A new target must start today or later. Past weeks keep the target they had.',
      });
    const saved: Target = {
      id: Math.max(...rows.map((r) => r.id)) + 1,
      ...t,
      setByEmail: 'manager@mock.local',
      createdAt: new Date().toISOString(),
    };
    rows = [...rows.filter((r) => r.effectiveFrom !== t.effectiveFrom), saved];
    return saved;
  },
};

export const targets = import.meta.env.VITE_USE_MOCK === 'true' ? mock : real;
