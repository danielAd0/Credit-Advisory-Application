import { ApiError } from './http';
import { consultants as staff, delay } from './mock';
import type {
  BudgetResponse,
  ConsultantReportRow,
  ConsultantsResponse,
  MonthsResponse,
  ProductsResponse,
  RangeBudgetResponse,
  RangeConsultantsResponse,
  RangeDay,
  RangeProductsResponse,
  RangeSummaryResponse,
  RangeWeekPart,
  ReportScope,
  SummaryResponse,
  WeeksResponse,
} from './reports';
import { mockTargetOn } from './targets';
import type { Role } from './types';

const WEEKS = ['2026-10-12', '2026-10-05'];
// Targets come from the mock Targets page: none until one is saved there.
const dailyTarget = (date: string) => mockTargetOn(date)?.dailyTarget ?? null;
const weeklyTarget = (monday: string) => mockTargetOn(monday)?.weeklyTarget ?? null;
const reached = (amount: number, target: number | null) =>
  target === null ? null : amount >= target;

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
    const target = own ? null : dailyTarget(addDays(week, d - 1));
    return {
      dayOfWeek: d,
      date: addDays(week, d - 1),
      transactionCount: q === 0 ? 0 : Math.round(q / 9000),
      clientCount: q === 0 ? 0 : Math.round(q / 9500),
      quotationTotal: q,
      bankedTotal: banked(week, d),
      dailyTarget: target,
      targetMet: reached(q, target),
    };
  });
  const q = sum(days.map((d) => d.quotationTotal));
  const wTarget = own ? null : weeklyTarget(week);
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
      weeklyTarget: wTarget,
      targetMet: reached(q, wTarget),
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

// ---------- any month or date range ----------

/** Mock sales run from 31 Aug to 18 Oct 2026; the last two weeks match the weekly mock. */
const FIRST_SALE = '2026-08-31';
const LAST_SALE = '2026-10-18';

const dayOfWeek = (date: string) => ((new Date(date + 'T00:00:00Z').getUTCDay() + 6) % 7) + 1;
const mondayOf = (date: string) => addDays(date, 1 - dayOfWeek(date));

const quotationOn = (date: string) => {
  const week = mondayOf(date);
  if (WEEKS.includes(week)) return quotation(week, dayOfWeek(date));
  if (date < FIRST_SALE || date > LAST_SALE) return 0;
  const base = [96000, 138000, 121000, 84000, 152000, 31000, 0][dayOfWeek(date) - 1];
  const wobble = 0.6 + (Number(date.slice(8, 10)) % 5) * 0.08;
  return Math.round(base * wobble * share());
};

const datesBetween = (from: string, to: string) => {
  const out: string[] = [];
  for (let d = from; d <= to; d = addDays(d, 1)) out.push(d);
  return out;
};

const badPeriod = (detail: string) =>
  new ApiError(400, detail, { type: 'urn:credit-advisory:report:invalid-period', detail });

const checkPeriod = (from: string, to: string) => {
  if (!from || !to) throw badPeriod('Choose a start and an end date.');
  if (to < from) throw badPeriod('The end date is before the start date.');
  if (datesBetween(from, to).length > 366) throw badPeriod('A report can cover at most 366 days.');
};

export async function months(): Promise<MonthsResponse> {
  await delay(300);
  const byMonth = new Map<string, { q: number; b: number; n: number }>();
  for (const d of datesBetween(FIRST_SALE, LAST_SALE)) {
    const q = quotationOn(d);
    if (q === 0) continue;
    const m = byMonth.get(d.slice(0, 7)) ?? { q: 0, b: 0, n: 0 };
    m.q += q;
    m.b += Math.round(q * 0.87);
    m.n += Math.max(1, Math.round(q / 9000));
    byMonth.set(d.slice(0, 7), m);
  }
  return {
    scope: scope(),
    months: [...byMonth.entries()]
      .sort(([a], [b]) => b.localeCompare(a))
      .map(([month, m]) => {
        const from = month + '-01';
        const next = new Date(from + 'T00:00:00Z');
        next.setUTCMonth(next.getUTCMonth() + 1, 0);
        return {
          month,
          from,
          to: next.toISOString().slice(0, 10),
          transactionCount: m.n,
          quotationTotal: m.q,
          bankedTotal: m.b,
        };
      }),
  };
}

export async function rangeSummary(from: string, to: string): Promise<RangeSummaryResponse> {
  await delay(300);
  checkPeriod(from, to);
  const own = scope() === 'OWN';
  const days: RangeDay[] = datesBetween(from, to).map((date) => {
    const q = quotationOn(date);
    const target = own ? null : dailyTarget(date);
    return {
      date,
      dayOfWeek: dayOfWeek(date),
      transactionCount: q === 0 ? 0 : Math.max(1, Math.round(q / 9000)),
      clientCount: q === 0 ? 0 : Math.max(1, Math.round(q / 9500)),
      quotationTotal: q,
      bankedTotal: Math.round(q * 0.87),
      dailyTarget: target,
      targetMet: reached(q, target),
    };
  });
  const weeks: RangeWeekPart[] = [];
  for (const d of days) {
    const monday = mondayOf(d.date);
    let w = weeks.at(-1);
    if (!w || w.weekStarting !== monday) {
      w = {
        weekStarting: monday,
        from: d.date,
        to: d.date,
        wholeWeek: false,
        transactionCount: 0,
        quotationTotal: 0,
        bankedTotal: 0,
        weeklyTarget: own ? null : weeklyTarget(monday),
        targetMet: null,
      };
      weeks.push(w);
    }
    w.to = d.date;
    w.transactionCount += d.transactionCount;
    w.quotationTotal += d.quotationTotal;
    w.bankedTotal += d.bankedTotal;
  }
  for (const w of weeks) {
    w.wholeWeek = w.from === w.weekStarting && w.to === addDays(w.weekStarting, 6);
    if (w.wholeWeek) w.targetMet = reached(w.quotationTotal, w.weeklyTarget);
  }
  const withSales = days.filter((d) => d.transactionCount > 0);
  const judged = withSales.filter((d) => d.targetMet !== null);
  return {
    scope: scope(),
    from,
    to,
    totals: {
      transactionCount: sum(days.map((d) => d.transactionCount)),
      clientCount: Math.max(0, sum(days.map((d) => d.clientCount)) - 3),
      unattributedCount: own ? null : withSales.length > 0 ? 2 : 0,
      quotationTotal: sum(days.map((d) => d.quotationTotal)),
      bankedTotal: sum(days.map((d) => d.bankedTotal)),
      daysWithSales: withSales.length,
      daysJudged: own ? null : judged.length,
      daysTargetMet: own || judged.length === 0 ? null : judged.filter((d) => d.targetMet).length,
    },
    days,
    weeks,
  };
}

const rangeTotal = (from: string, to: string) =>
  sum(datesBetween(from, to).map((d) => quotationOn(d)));

export async function rangeConsultants(
  from: string,
  to: string,
): Promise<RangeConsultantsResponse> {
  await delay(300);
  checkPeriod(from, to);
  const own = scope() === 'OWN';
  const total = rangeTotal(from, to);
  const people = own ? staff.slice(0, 1) : staff.slice(0, 5);
  const rows: RangeConsultantsResponse['consultants'] = people.map((c, n) => {
    const q = Math.round((total / (own ? 1 : 5)) * (1 - n * 0.12));
    const count = q === 0 ? 0 : Math.max(1, Math.round(q / 9000));
    return {
      consultant: { id: c.id, consultantCode: c.consultantCode, fullName: c.fullName },
      transactionCount: count,
      clientCount: Math.max(0, count - 1),
      quotationTotal: q,
      bankedTotal: Math.round(q * 0.87),
    };
  });
  if (!own && total > 0)
    rows.push({
      consultant: null,
      transactionCount: 1,
      clientCount: 1,
      quotationTotal: 9000,
      bankedTotal: 7800,
    });
  return { scope: scope(), from, to, consultants: rows };
}

export async function rangeProducts(
  from: string,
  to: string,
  consultantId?: number,
): Promise<RangeProductsResponse> {
  await delay(300);
  checkPeriod(from, to);
  const own = scope() === 'OWN';
  const f = consultantId && !own ? 0.2 : 1;
  const total = rangeTotal(from, to) * f;
  const shares = [0.62, 0.19, 0.1, 0.09, 0];
  return {
    scope: scope(),
    from,
    to,
    consultantId: own ? 1 : (consultantId ?? null),
    products: PRODUCTS.map(([code, name], i) => {
      const amount = Math.round(total * shares[i]);
      return {
        product: { id: i + 1, code, name },
        lineCount: amount === 0 ? 0 : Math.max(1, Math.round(amount / 1500)),
        totalAmount: amount,
      };
    }),
  };
}

export async function rangeBudget(from: string, to: string): Promise<RangeBudgetResponse> {
  await delay(300);
  checkPeriod(from, to);
  if (scope() === 'OWN')
    throw new ApiError(403, 'Only managers and administrators can see this report.', {
      type: 'urn:credit-advisory:auth:forbidden',
      detail: 'Only managers and administrators can see this report.',
    });
  const sales = datesBetween(from, to).filter((d) => quotationOn(d) > 0).length;
  return {
    from,
    to,
    structures: ['MAFORI CREDIT', 'VITAL CREDIT'].map((name, n) => ({
      budgetStructure: name,
      entryCount: sales * (n + 1),
      loanAmountTotal: sales * 3500 * (n + 1),
    })),
  };
}
