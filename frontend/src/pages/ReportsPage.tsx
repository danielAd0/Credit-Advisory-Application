import { useEffect, useState, type ReactNode } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  data,
  detailOf,
  reports,
  type BudgetResponse,
  type ConsultantSummary,
  type ConsultantsResponse,
  type MonthListItem,
  type ProductsResponse,
  type RangeSummaryResponse,
  type SummaryResponse,
  type WeekListItem,
} from '../api';
import { hasAnyRole, useSession } from '../auth/useAuth';
import {
  RangeBudget,
  RangeChart,
  RangeConsultants,
  RangeKpis,
  RangeProducts,
  RangeSummary,
} from './RangeReport';
import {
  compact,
  DAY_NAMES,
  day,
  Download,
  Failed,
  KpiRow,
  monthLabel,
  periodLabel,
  rand,
  useReport,
} from './reportShared';
import './reports.css';

type Tab = 'summary' | 'consultants' | 'products' | 'budget';

interface GridRow {
  key: string;
  label: ReactNode;
  cells: number[];
  total: number;
}

function DayGrid({
  firstColumn,
  rows,
  money,
  empty,
}: {
  firstColumn: string;
  rows: GridRow[];
  money: boolean;
  empty: string;
}) {
  if (rows.length === 0) return <p className="muted">{empty}</p>;
  const show = (n: number) => (money ? rand(n) : String(n));
  const totals = DAY_NAMES.map(() => 0);
  for (const r of rows) r.cells.forEach((c, i) => (totals[i] += c));
  return (
    <div className="table-wrap">
      <table className="rp-grid">
        <thead>
          <tr>
            <th>{firstColumn}</th>
            {DAY_NAMES.map((d) => (
              <th key={d} className="num">
                {d}
              </th>
            ))}
            <th className="num">Week</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.key}>
              <td>{r.label}</td>
              {r.cells.map((c, i) => (
                <td key={i} className={`num ${c === 0 ? 'muted' : ''}`}>
                  {show(c)}
                </td>
              ))}
              <td className="num">
                <strong>{show(r.total)}</strong>
              </td>
            </tr>
          ))}
        </tbody>
        {rows.length > 1 && (
          <tfoot>
            <tr>
              <th>All</th>
              {totals.map((t, i) => (
                <th key={i} className="num">
                  {show(t)}
                </th>
              ))}
              <th className="num">{show(rows.reduce((a, r) => a + r.total, 0))}</th>
            </tr>
          </tfoot>
        )}
      </table>
    </div>
  );
}

function Kpis({ r }: { r: SummaryResponse }) {
  const own = r.scope === 'OWN';
  const label = `week of ${day(r.weekStarting)}`;
  return (
    <KpiRow
      items={[
        { label: 'Quotation', value: rand(r.week.quotationTotal), note: `Quoted, ${label}` },
        { label: 'Banked', value: rand(r.week.bankedTotal), note: `Banked, ${label}` },
        {
          label: 'Sales',
          value: r.week.transactionCount.toLocaleString('en-ZA'),
          note: own ? `Your sales, ${label}` : `Sales, ${label}`,
        },
        {
          label: 'Clients',
          value: r.week.clientCount.toLocaleString('en-ZA'),
          note: `Clients, ${label}`,
        },
      ]}
    />
  );
}

function DailyChart({ r }: { r: SummaryResponse }) {
  const days = DAY_NAMES.map((name, i) => {
    const d = r.days.find((x) => x.dayOfWeek === i + 1);
    return { name, quotation: d?.quotationTotal ?? 0, banked: d?.bankedTotal ?? 0 };
  });
  const top = Math.max(1, ...days.flatMap((d) => [d.quotation, d.banked]));
  const first = r.days.at(0)?.date;
  const last = r.days.at(-1)?.date;
  const range =
    first && last
      ? `${new Date(first).toLocaleDateString('en-ZA', { day: 'numeric' })}–${new Date(
          last,
        ).toLocaleDateString('en-ZA', { day: 'numeric', month: 'short', year: 'numeric' })}`
      : '';
  return (
    <figure className="rp-card rp-daily">
      <figcaption className="rp-card-head">
        <div>
          <h2>Daily quotations and banked</h2>
          <p className="muted">
            {range}
            {range && ' · '}exact figures in the summary below
          </p>
        </div>
        <ul className="rp-legend">
          <li>
            <span className="rp-swatch banked" /> Banked
          </li>
          <li>
            <span className="rp-swatch quotation" /> Quotation
          </li>
        </ul>
      </figcaption>
      <div className="rp-cols" role="img" aria-label="Daily banked and quotation amounts">
        {days.map((d) => {
          const empty = d.quotation === 0 && d.banked === 0;
          return (
            <div key={d.name} className="rp-col">
              <div className="rp-col-vals" aria-hidden="true">
                {empty ? (
                  <span>R0</span>
                ) : (
                  <>
                    <span>{compact(d.banked)}</span>
                    <span>{compact(d.quotation)}</span>
                  </>
                )}
              </div>
              <div className={`rp-col-bars ${empty ? 'empty' : ''}`}>
                {empty ? (
                  <>
                    <span className="rp-colbar ghost" />
                    <span className="rp-colbar ghost" />
                  </>
                ) : (
                  <>
                    <span
                      className="rp-colbar banked"
                      style={{ height: `${(d.banked / top) * 100}%` }}
                    />
                    <span
                      className="rp-colbar quotation"
                      style={{ height: `${(d.quotation / top) * 100}%` }}
                    />
                  </>
                )}
              </div>
              <span className="rp-col-day">{d.name}</span>
            </div>
          );
        })}
      </div>
      <p className="rp-foot">Chart labels rounded to the nearest R1k</p>
    </figure>
  );
}

function QuotedBanked({
  quotation,
  banked,
  note,
}: {
  quotation: number;
  banked: number;
  note: string;
}) {
  const top = Math.max(1, quotation, banked);
  const rows = [
    { key: 'quotation', label: 'Quotation', value: quotation },
    { key: 'banked', label: 'Banked', value: banked },
  ];
  return (
    <figure className="rp-card rp-qb">
      <figcaption className="rp-card-head">
        <div>
          <h2>Quoted and banked</h2>
          <p className="muted">{note}</p>
        </div>
      </figcaption>
      {rows.map((row) => (
        <div key={row.key} className="rp-hbar">
          <div className="rp-hbar-head">
            <span>{row.label}</span>
            <strong>{rand(row.value)}</strong>
          </div>
          <div className="rp-hbar-track">
            <div
              className={`rp-hbar-fill ${row.key}`}
              style={{ width: `${(row.value / top) * 100}%` }}
            />
          </div>
        </div>
      ))}
    </figure>
  );
}

function Summary({ r }: { r: SummaryResponse }) {
  const own = r.scope === 'OWN';
  const pct = r.week.weeklyTarget
    ? Math.min(100, (r.week.quotationTotal / r.week.weeklyTarget) * 100)
    : 0;
  return (
    <>
      <div className="rp-card-head">
        <div>
          <h2>Summary</h2>
          <p className="muted">Daily sales, clients, quotations, and banked amounts.</p>
        </div>
        {!own && r.week.weeklyTarget !== null && (
          <div className={`rp-target ${r.week.targetMet ? 'met' : ''}`}>
            <p>
              Target for that week {rand(r.week.weeklyTarget)}:{' '}
              {r.week.targetMet ? 'met' : 'not met'} ({Math.round(pct)}%)
            </p>
            <div
              className="rp-bar"
              role="img"
              aria-label={`${Math.round(pct)} percent of the weekly target`}
            >
              <div className="rp-fill" style={{ width: `${pct}%` }} />
            </div>
          </div>
        )}
        {!own && r.week.weeklyTarget === null && (
          <div className="rp-target">
            <p>Target for that week: not configured</p>
          </div>
        )}
      </div>
      {!own && r.week.unattributedCount ? (
        <p className="rp-warn">
          {r.week.unattributedCount} sale{r.week.unattributedCount === 1 ? ' has' : 's have'} no
          consultant in the register.
        </p>
      ) : null}
      <div className="table-wrap">
        <table className="rp-table">
          <thead>
            <tr>
              <th>Day</th>
              <th>Date</th>
              <th>Sales</th>
              <th>Clients</th>
              <th>Quotation</th>
              <th>Banked</th>
              {!own && <th>Daily target</th>}
            </tr>
          </thead>
          <tbody>
            {r.days.map((d) => (
              <tr key={d.dayOfWeek}>
                <td>{DAY_NAMES[d.dayOfWeek - 1]}</td>
                <td>{day(d.date)}</td>
                <td>{d.transactionCount}</td>
                <td>{d.clientCount}</td>
                <td className="nowrap">{rand(d.quotationTotal)}</td>
                <td className="nowrap">{rand(d.bankedTotal)}</td>
                {!own && (
                  <td>
                    {d.dailyTarget === null ? (
                      <span className="muted">Not configured</span>
                    ) : (
                      <span className={d.targetMet ? 'rp-yes' : 'rp-no'}>
                        {d.targetMet ? 'Met' : 'Not met'}
                      </span>
                    )}
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="rp-foot">
        Clients are counted once per day, so the week figure can be lower than the sum of the days.
      </p>
    </>
  );
}

function Consultants({ week }: { week: string }) {
  const { value: r, error } = useReport<ConsultantsResponse>(
    () => reports.consultants(week),
    [week],
  );
  const [measure, setMeasure] = useState<'quotation' | 'banked'>('quotation');
  if (error) return <Failed message={error} />;
  if (!r) return <p className="muted">Loading...</p>;
  const rows: GridRow[] = r.consultants.map((c) => ({
    key: c.consultant?.id.toString() ?? 'none',
    label: c.consultant ? (
      <>
        {c.consultant.fullName} <span className="muted mono">{c.consultant.consultantCode}</span>
      </>
    ) : (
      <span className="muted">No consultant</span>
    ),
    cells: DAY_NAMES.map((_, i) => {
      const d = c.days.find((x) => x.dayOfWeek === i + 1);
      return d ? (measure === 'quotation' ? d.quotationTotal : d.bankedTotal) : 0;
    }),
    total: measure === 'quotation' ? c.week.quotationTotal : c.week.bankedTotal,
  }));
  return (
    <>
      <div className="rp-toggle">
        <button
          type="button"
          className="btn"
          aria-pressed={measure === 'quotation'}
          onClick={() => setMeasure('quotation')}
        >
          Quotation
        </button>
        <button
          type="button"
          className="btn"
          aria-pressed={measure === 'banked'}
          onClick={() => setMeasure('banked')}
        >
          Banked
        </button>
      </div>
      <DayGrid firstColumn="Consultant" rows={rows} money empty="Nobody sold anything this week." />
    </>
  );
}

function Products({
  week,
  manager,
  consultantId,
  setConsultantId,
}: {
  week: string;
  manager: boolean;
  consultantId: string;
  setConsultantId: (id: string) => void;
}) {
  const [people, setPeople] = useState<ConsultantSummary[]>([]);
  useEffect(() => {
    if (!manager) return;
    let live = true;
    data
      .listConsultants()
      .then((r) => live && setPeople(r))
      .catch(() => undefined); // the filter is optional
    return () => {
      live = false;
    };
  }, [manager]);
  const { value: r, error } = useReport<ProductsResponse>(
    () => reports.products(week, consultantId ? Number(consultantId) : undefined),
    [week, consultantId],
  );
  return (
    <>
      {manager && (
        <div className="rp-pick">
          <label htmlFor="rp-consultant">Consultant</label>
          <select
            id="rp-consultant"
            value={consultantId}
            onChange={(e) => setConsultantId(e.target.value)}
          >
            <option value="">Everyone</option>
            {people.map((c) => (
              <option key={c.id} value={c.id}>
                {c.fullName}
              </option>
            ))}
          </select>
        </div>
      )}
      {error && <Failed message={error} />}
      {!error && !r && <p className="muted">Loading...</p>}
      {r && (
        <>
          <DayGrid
            firstColumn="Product"
            money
            empty="No products."
            rows={r.products.map((p) => ({
              key: p.product.code,
              label: p.product.name,
              cells: DAY_NAMES.map(
                (_, i) => p.days.find((d) => d.dayOfWeek === i + 1)?.totalAmount ?? 0,
              ),
              total: p.week.totalAmount,
            }))}
          />
          <p className="muted">
            Product amounts are not the same as quotation or banked, because some products count
            toward only one of them.
          </p>
        </>
      )}
    </>
  );
}

function Budget({ week }: { week: string }) {
  const { value: r, error } = useReport<BudgetResponse>(() => reports.budget(week), [week]);
  if (error) return <Failed message={error} />;
  if (!r) return <p className="muted">Loading...</p>;
  return (
    <>
      <DayGrid
        firstColumn="Budget structure"
        money
        empty="No budget structures this week."
        rows={r.structures.map((s) => ({
          key: s.budgetStructure,
          label: s.budgetStructure,
          cells: DAY_NAMES.map(
            (_, i) => s.days.find((d) => d.dayOfWeek === i + 1)?.loanAmountTotal ?? 0,
          ),
          total: s.week.loanAmountTotal,
        }))}
      />
    </>
  );
}

type Mode = 'week' | 'day' | 'month' | 'custom';

const pad = (n: number) => String(n).padStart(2, '0');
const isoDate = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const DATE = /^\d{4}-\d{2}-\d{2}$/;
const MAX_DAYS = 366;

const daysBetween = (from: string, to: string) =>
  Math.round((Date.parse(to + 'T00:00:00Z') - Date.parse(from + 'T00:00:00Z')) / 86_400_000) + 1;

/** Why a custom range can't be shown, or null when it's fine. */
function rangeProblem(from: string, to: string) {
  if (!DATE.test(from) || !DATE.test(to)) return 'Choose a start and an end date.';
  if (to < from) return 'The end date is before the start date.';
  if (daysBetween(from, to) > MAX_DAYS) return `A report can cover at most ${MAX_DAYS} days.`;
  return null;
}

export default function ReportsPage() {
  const session = useSession();
  const manager = hasAnyRole(session, ['ADMIN', 'MANAGER']);
  const [params] = useSearchParams();
  // Links can open a period: ?week=2026-10-05, ?day=2026-10-07, ?month=2026-10,
  // ?from=...&to=..., or just ?period=day / month / custom.
  const [mode, setMode] = useState<Mode>(() =>
    (params.get('from') && params.get('to')) || params.get('period') === 'custom'
      ? 'custom'
      : params.get('month') || params.get('period') === 'month'
        ? 'month'
        : params.get('day') || params.get('period') === 'day'
          ? 'day'
          : 'week',
  );
  const [weeks, setWeeks] = useState<WeekListItem[] | null>(null);
  const [months, setMonths] = useState<MonthListItem[] | null>(null);
  const [scope, setScope] = useState<'ALL' | 'OWN'>('ALL');
  const [week, setWeek] = useState(() => params.get('week') ?? '');
  const [month, setMonth] = useState(() => params.get('month') ?? '');
  const today = isoDate(new Date());
  const [oneDay, setOneDay] = useState(() =>
    DATE.test(params.get('day') ?? '') ? params.get('day')! : today,
  );
  const [draft, setDraft] = useState(() => ({
    from: params.get('from') ?? today.slice(0, 8) + '01',
    to: params.get('to') ?? today,
  }));
  const [custom, setCustom] = useState<{ from: string; to: string } | null>(() =>
    params.get('from') && params.get('to') && !rangeProblem(params.get('from')!, params.get('to')!)
      ? { from: params.get('from')!, to: params.get('to')! }
      : null,
  );
  const [tab, setTab] = useState<Tab>('summary');
  const [consultantId, setConsultantId] = useState('');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    document.title = 'Reports - Credit Advisory';
  }, []);

  useEffect(() => {
    let live = true;
    reports
      .weeks()
      .then((r) => {
        if (!live) return;
        setWeeks(r.weeks);
        setScope(r.scope);
        setWeek((w) =>
          w && r.weeks.some((x) => x.weekStarting === w) ? w : (r.weeks.at(0)?.weekStarting ?? ''),
        );
      })
      .catch((err) => live && setError(detailOf(err) ?? 'Could not load the weeks.'));
    return () => {
      live = false;
    };
  }, []);

  useEffect(() => {
    if (mode !== 'month' || months !== null) return;
    let live = true;
    reports
      .months()
      .then((r) => {
        if (!live) return;
        setMonths(r.months);
        setMonth((m) =>
          m && r.months.some((x) => x.month === m) ? m : (r.months.at(0)?.month ?? ''),
        );
      })
      .catch((err) => live && setError(detailOf(err) ?? 'Could not load the months.'));
    return () => {
      live = false;
    };
  }, [mode, months]);

  // The period the month and custom modes report on.
  const chosenMonth = months?.find((m) => m.month === month);
  const range =
    mode === 'month' && chosenMonth
      ? { from: chosenMonth.from, to: chosenMonth.to }
      : mode === 'custom'
        ? custom
        : mode === 'day' && DATE.test(oneDay)
          ? { from: oneDay, to: oneDay }
          : null;
  const rangeName =
    mode === 'month' && chosenMonth
      ? monthLabel(chosenMonth.month)
      : range
        ? periodLabel(range.from, range.to)
        : '';

  const summary = useReport<SummaryResponse | null>(
    () => (mode === 'week' && week ? reports.summary(week) : Promise.resolve(null)),
    [mode, week],
  );
  const rangeSummary = useReport<RangeSummaryResponse | null>(
    () => (range ? reports.rangeSummary(range.from, range.to) : Promise.resolve(null)),
    [range?.from, range?.to],
  );

  const tabs: { id: Tab; label: string }[] = [
    { id: 'summary', label: 'Summary' },
    { id: 'consultants', label: 'Consultants' },
    { id: 'products', label: 'Products' },
    ...(manager ? [{ id: 'budget' as Tab, label: 'Budget structure' }] : []),
  ];

  const csvName: Record<Tab, string> = {
    summary: 'summary.csv',
    consultants: 'consultants.csv',
    products: 'products.csv',
    budget: 'budget-structures.csv',
  };
  const consultantQuery = tab === 'products' && consultantId ? `consultantId=${consultantId}` : '';
  const csvHref =
    mode === 'week'
      ? week
        ? `/api/reports/weeks/${week}/${csvName[tab]}${consultantQuery ? `?${consultantQuery}` : ''}`
        : null
      : range
        ? `/api/reports/range/${csvName[tab]}?from=${range.from}&to=${range.to}${
            consultantQuery ? `&${consultantQuery}` : ''
          }`
        : null;

  const draftProblem = rangeProblem(draft.from, draft.to);
  const ready = mode === 'week' ? Boolean(week) : Boolean(range);

  return (
    <div className="rp">
      <header className="rp-head">
        <div>
          <p className="eyebrow">Credit Advisory / Reports</p>
          <h1>Reports</h1>
          <p className="muted">
            {scope === 'OWN'
              ? 'Your own sales, from the imported weekly registers.'
              : 'Company figures from the imported weekly registers.'}
          </p>
        </div>
        {csvHref && <Download href={csvHref} />}
      </header>

      {error && <Failed message={error} />}
      {!error && weeks === null && <p className="muted">Loading...</p>}
      {weeks && weeks.length === 0 && (
        <p className="muted">
          No weekly register has been imported yet. Reports appear after a register is confirmed.
        </p>
      )}
      {weeks && weeks.length > 0 && (
        <>
          <div className="rp-period">
            <div className="rp-toggle rp-mode" role="group" aria-label="Report period">
              {(
                [
                  ['week', 'Week'],
                  ['day', 'Day'],
                  ['month', 'Month'],
                  ['custom', 'Date range'],
                ] as const
              ).map(([id, label]) => (
                <button
                  key={id}
                  type="button"
                  className="btn"
                  aria-pressed={mode === id}
                  onClick={() => setMode(id)}
                >
                  {label}
                </button>
              ))}
            </div>

            {mode === 'week' && (
              <div className="rp-week">
                <label htmlFor="rp-week">Week</label>
                <select id="rp-week" value={week} onChange={(e) => setWeek(e.target.value)}>
                  {weeks.map((w) => (
                    <option key={w.weekStarting} value={w.weekStarting}>
                      Week of {day(w.weekStarting)} ({rand(w.quotationTotal)})
                    </option>
                  ))}
                </select>
              </div>
            )}

            {mode === 'day' && (
              <div className="rp-range">
                <label>
                  Day
                  <input
                    type="date"
                    value={oneDay}
                    onChange={(e) => e.target.value && setOneDay(e.target.value)}
                  />
                </label>
              </div>
            )}

            {mode === 'month' && months === null && <p className="muted">Loading...</p>}
            {mode === 'month' && months && months.length === 0 && (
              <p className="muted">No sales have been imported yet.</p>
            )}
            {mode === 'month' && months && months.length > 0 && (
              <div className="rp-week">
                <label htmlFor="rp-month">Month</label>
                <select id="rp-month" value={month} onChange={(e) => setMonth(e.target.value)}>
                  {months.map((m) => (
                    <option key={m.month} value={m.month}>
                      {monthLabel(m.month)} ({rand(m.quotationTotal)})
                    </option>
                  ))}
                </select>
              </div>
            )}

            {mode === 'custom' && (
              <form
                className="rp-range"
                onSubmit={(e) => {
                  e.preventDefault();
                  if (!draftProblem) setCustom({ ...draft });
                }}
              >
                <label>
                  From
                  <input
                    type="date"
                    value={draft.from}
                    max={draft.to || undefined}
                    onChange={(e) => setDraft((d) => ({ ...d, from: e.target.value }))}
                  />
                </label>
                <label>
                  To
                  <input
                    type="date"
                    value={draft.to}
                    min={draft.from || undefined}
                    onChange={(e) => setDraft((d) => ({ ...d, to: e.target.value }))}
                  />
                </label>
                <button type="submit" className="btn primary" disabled={draftProblem !== null}>
                  Show report
                </button>
                {draftProblem && <span className="muted">{draftProblem}</span>}
              </form>
            )}
          </div>

          {mode !== 'week' && range && (
            <p className="rp-period-note muted">
              Showing <strong>{rangeName}</strong>.{' '}
              {mode === 'day'
                ? 'Imported and typed-in sales dated that day.'
                : 'Sales count on their own date, so a week that runs into the next month is split between the two months.'}
            </p>
          )}
          {mode === 'custom' && !custom && (
            <p className="muted">Choose the dates and press Show report.</p>
          )}

          {mode === 'week' && summary.error && tab === 'summary' && (
            <Failed message={summary.error} />
          )}
          {mode === 'week' && summary.value && tab === 'summary' && (
            <>
              <Kpis r={summary.value} />
              <div className="rp-charts">
                <DailyChart r={summary.value} />
                <QuotedBanked
                  quotation={summary.value.week.quotationTotal}
                  banked={summary.value.week.bankedTotal}
                  note="Weekly totals on the same scale"
                />
              </div>
            </>
          )}

          {mode !== 'week' && rangeSummary.error && tab === 'summary' && (
            <Failed message={rangeSummary.error} />
          )}
          {mode !== 'week' && rangeSummary.value && tab === 'summary' && (
            <>
              <RangeKpis r={rangeSummary.value} label={rangeName} />
              <div className="rp-charts">
                <RangeChart r={rangeSummary.value} />
                <QuotedBanked
                  quotation={rangeSummary.value.totals.quotationTotal}
                  banked={rangeSummary.value.totals.bankedTotal}
                  note="Period totals on the same scale"
                />
              </div>
            </>
          )}

          {ready && (
            <>
              <div className="rp-tabs" role="tablist" aria-label="Report">
                {tabs.map((t) => (
                  <button
                    key={t.id}
                    type="button"
                    role="tab"
                    className="rp-tab"
                    aria-selected={tab === t.id}
                    onClick={() => setTab(t.id)}
                  >
                    {t.label}
                  </button>
                ))}
              </div>

              <section
                className="rp-card rp-panel"
                role="tabpanel"
                aria-label={tabs.find((t) => t.id === tab)?.label}
              >
                {mode === 'week' && (
                  <>
                    {tab === 'summary' &&
                      (summary.value ? (
                        <Summary r={summary.value} />
                      ) : (
                        !summary.error && <p className="muted">Loading...</p>
                      ))}
                    {tab === 'consultants' && <Consultants week={week} />}
                    {tab === 'products' && (
                      <Products
                        week={week}
                        manager={manager}
                        consultantId={consultantId}
                        setConsultantId={setConsultantId}
                      />
                    )}
                    {tab === 'budget' && manager && <Budget week={week} />}
                  </>
                )}
                {mode !== 'week' && range && (
                  <>
                    {tab === 'summary' &&
                      (rangeSummary.value ? (
                        <RangeSummary r={rangeSummary.value} />
                      ) : (
                        !rangeSummary.error && <p className="muted">Loading...</p>
                      ))}
                    {tab === 'consultants' && <RangeConsultants from={range.from} to={range.to} />}
                    {tab === 'products' && (
                      <RangeProducts
                        from={range.from}
                        to={range.to}
                        manager={manager}
                        consultantId={consultantId}
                        setConsultantId={setConsultantId}
                      />
                    )}
                    {tab === 'budget' && manager && <RangeBudget from={range.from} to={range.to} />}
                  </>
                )}
              </section>
            </>
          )}
        </>
      )}
    </div>
  );
}
