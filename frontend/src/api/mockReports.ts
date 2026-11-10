import { ApiError } from './http';
import { consultants as staff, delay } from './mock';
import type {
  BudgetResponse,
  ConsultantReportRow,
  ConsultantsResponse,
  ProductsResponse,
  ReportScope,
  SummaryResponse,
  WeeksResponse,
} from './reports';
import type { Role } from './types';

const WEEKS = ['2026-10-12', '2026-10-05'];
const DAILY_TARGET = 120000;
const WEEKLY_TARGET = 600000;

const scope = (): ReportScope =>
  ((import.meta.env.VITE_MOCK_ROLE as Role | undefined) ?? 'MANAGER') === 'CONSULTANT'
    ? 'OWN'
    : 'ALL';

const share = () => (scope() === 'OWN' ? 0.1 : 1);

const known = (week: string) => {
  const i = WEEKS.indexOf(week);
  if (i === -1)
    throw new ApiError(404, 'No register has been imported for that week.', {
      type: 'urn:credit-advisory:report:week-not-found',
      detail: 'No register has been imported for that week.',
    });
  return i;
};

const quotation = (week: string, day: number) => {
  const i = known(week);
  const base = [96000, 138000, 121000, 84000, 152000, 31000, 0][day - 1];
  return Math.round(base * (i === 0 ? 1 : 0.8) * share());
};
const banked = (week: string, day: number) => Math.round(quotation(week, day) * 0.87);

const addDays = (week: string, n: number) => {
  const d = new Date(week + 'T00:00:00Z');
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
};

const DAYS = [1, 2, 3, 4, 5, 6, 7];
const sum = (xs: number[]) => xs.reduce((a, b) => a + b, 0);

export async function weeks(): Promise<WeeksResponse> {
  await delay(300);
  return {
    scope: scope(),
    weeks: WEEKS.map((w) => ({
      weekStarting: w,
      transactionCount: Math.round(60 * share()),
      quotationTotal: sum(DAYS.map((d) => quotation(w, d))),
      bankedTotal: sum(DAYS.map((d) => banked(w, d))),
    })),
  };
}

export async function summary(week: string): Promise<SummaryResponse> {
  await delay(300);
  known(week);
  const own = scope() === 'OWN';
  const days = DAYS.map((d) => {
    const q = quotation(week, d);
    return {
      dayOfWeek: d,
      date: addDays(week, d - 1),
      transactionCount: q === 0 ? 0 : Math.round(q / 9000),
      clientCount: q === 0 ? 0 : Math.round(q / 9500),
      quotationTotal: q,
      bankedTotal: banked(week, d),
      dailyTarget: own ? null : DAILY_TARGET,
      targetMet: own ? null : q >= DAILY_TARGET,
    };
  });
  const q = sum(days.map((d) => d.quotationTotal));
  return {
    scope: scope(),
    weekStarting: week,
    weekEnding: addDays(week, 6),
    days,
    week: {
      transactionCount: sum(days.map((d) => d.transactionCount)),
      clientCount: sum(days.map((d) => d.clientCount)) - 3,
      unattributedCount: own ? null : 2,
      quotationTotal: q,
      bankedTotal: sum(days.map((d) => d.bankedTotal)),
      weeklyTarget: own ? null : WEEKLY_TARGET,
      targetMet: own ? null : q >= WEEKLY_TARGET,
    },
  };
}

export async function consultants(week: string): Promise<ConsultantsResponse> {
  await delay(300);
  known(week);
  const own = scope() === 'OWN';
  const people = own ? staff.slice(0, 1) : staff.slice(0, 5);
  const rows: ConsultantReportRow[] = people.map((c, n) => {
    const days = DAYS.map((d) => {
      const q = Math.round((quotation(week, d) / (own ? 1 : 5)) * (1 - n * 0.12));
      return {
        dayOfWeek: d,
        transactionCount: q === 0 ? 0 : Math.max(1, Math.round(q / 9000)),
        quotationTotal: q,
        bankedTotal: Math.round(q * 0.87),
      };
    });
    return {
      consultant: { id: c.id, consultantCode: c.consultantCode, fullName: c.fullName },
      days,
      week: {
        transactionCount: sum(days.map((d) => d.transactionCount)),
        clientCount: Math.max(0, sum(days.map((d) => d.transactionCount)) - 1),
        quotationTotal: sum(days.map((d) => d.quotationTotal)),
        bankedTotal: sum(days.map((d) => d.bankedTotal)),
      },
    };
  });
  if (!own) {
    const days = DAYS.map((d) => ({
      dayOfWeek: d,
      transactionCount: d === 2 ? 1 : 0,
      quotationTotal: d === 2 ? 9000 : 0,
      bankedTotal: d === 2 ? 7800 : 0,
    }));
    rows.push({
      consultant: null,
      days,
      week: { transactionCount: 1, clientCount: 1, quotationTotal: 9000, bankedTotal: 7800 },
    });
  }
  return { scope: scope(), weekStarting: week, consultants: rows };
}

const PRODUCTS = [
  ['COMBO', 'Combo', 40000],
  ['IVC', 'IVC', 12000],
  ['INSURANCE', 'Insurance', 6750],
  ['MAFORI_CARD', 'Mafori Card', 5250],
  ['CREDIT_RESCUE', 'Credit Rescue', 0],
] as const;

export async function products(week: string, consultantId?: number): Promise<ProductsResponse> {
  await delay(300);
  known(week);
  const own = scope() === 'OWN';
  const f = own ? 0.1 : consultantId ? 0.2 : 1;
  return {
    scope: scope(),
    weekStarting: week,
    consultantId: own ? 1 : (consultantId ?? null),
    products: PRODUCTS.map(([code, name, total], i) => {
      const days = DAYS.map((d) => {
        const amount =
          d >= 6 || total === 0 ? 0 : Math.round((total / 5) * f * (0.6 + (d % 3) * 0.3));
        return {
          dayOfWeek: d,
          lineCount: amount === 0 ? 0 : Math.max(1, Math.round(amount / 1500)),
          totalAmount: amount,
        };
      });
      return {
        product: { id: i + 1, code, name },
        days,
        week: {
          lineCount: sum(days.map((d) => d.lineCount)),
          totalAmount: sum(days.map((d) => d.totalAmount)),
        },
      };
    }),
  };
}

export async function budget(week: string): Promise<BudgetResponse> {
  await delay(300);
  known(week);
  if (scope() === 'OWN')
    throw new ApiError(403, 'Only managers and administrators can see this report.', {
      type: 'urn:credit-advisory:auth:forbidden',
      detail: 'Only managers and administrators can see this report.',
    });
  return {
    weekStarting: week,
    structures: ['VITAL CREDIT', 'MAFORI CREDIT'].map((name, n) => {
      const days = DAYS.map((d) => ({
        dayOfWeek: d,
        entryCount: d <= 5 ? 1 + ((d + n) % 2) : 0,
        loanAmountTotal: d <= 5 ? (2000 + d * 500) * (n + 1) : 0,
      }));
      return {
        budgetStructure: name,
        days,
        week: {
          entryCount: sum(days.map((d) => d.entryCount)),
          loanAmountTotal: sum(days.map((d) => d.loanAmountTotal)),
        },
      };
    }),
  };
}
