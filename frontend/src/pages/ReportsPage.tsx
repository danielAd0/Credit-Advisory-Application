import { useEffect, useState, type ReactNode } from 'react';
import {
  data,
  detailOf,
  features,
  reports,
  type BudgetResponse,
  type ConsultantSummary,
  type ConsultantsResponse,
  type ProductsResponse,
  type SummaryResponse,
  type WeekListItem,
} from '../api';
import { hasAnyRole, useSession } from '../auth/useAuth';
import './reports.css';

const DAY_NAMES = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

const rand = (n: number, decimals = 0) =>
  n.toLocaleString('en-ZA', {
    style: 'currency',
    currency: 'ZAR',
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  });
const day = (iso: string) => new Date(iso).toLocaleDateString('en-ZA');

type Tab = 'summary' | 'consultants' | 'products' | 'budget';

function useReport<T>(load: () => Promise<T>, deps: unknown[]) {
  const [state, setState] = useState<{ value: T | null; error: string | null; key: string }>({
    value: null,
    error: null,
    key: '',
  });
  const key = JSON.stringify(deps);
  useEffect(() => {
    let live = true;
    load()
      .then((value) => live && setState({ value, error: null, key }))
      .catch(
        (err) =>
          live &&
          setState({ value: null, error: detailOf(err) ?? 'Could not load the report.', key }),
      );
    return () => {
      live = false;
    };
  }, [key]);
  const current = state.key === key;
  return { value: current ? state.value : null, error: current ? state.error : null };
}

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

function Download({ path }: { path: string }) {
  if (!features.reportExport) return null;
  return (
    <a className="btn primary rp-download" href={`/api/reports/weeks/${path}`}>
      Download as CSV
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
        <path d="M12 4v14M6 12l6 6 6-6" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    </a>
  );
}

const compact = (n: number) =>
  n >= 1_000_000
    ? `R${+(n / 1_000_000).toFixed(1)}m`
    : n >= 1000
      ? `R${Math.round(n / 1000)}k`
      : `R${Math.round(n)}`;

function Kpis({ r }: { r: SummaryResponse }) {
  const own = r.scope === 'OWN';
  const items = [
    { label: 'Quotation', value: rand(r.week.quotationTotal), note: 'Quoted this week' },
    { label: 'Banked', value: rand(r.week.bankedTotal), note: 'Banked this week' },
    {
      label: 'Sales',
      value: r.week.transactionCount.toLocaleString('en-ZA'),
      note: own ? 'Your sales this week' : 'Sales this week',
    },
    {
      label: 'Clients',
      value: r.week.clientCount.toLocaleString('en-ZA'),
      note: 'Clients for the week',
    },
  ];
  return (
    <div className="rp-kpis">
      {items.map((k) => (
        <div key={k.label} className="rp-kpi">
          <span className="rp-kpi-label">{k.label}</span>
          <strong>{k.value}</strong>
          <span className="rp-kpi-note">{k.note}</span>
        </div>
      ))}
    </div>
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

function QuotedBanked({ r }: { r: SummaryResponse }) {
  const top = Math.max(1, r.week.quotationTotal, r.week.bankedTotal);
  const rows = [
    { key: 'quotation', label: 'Quotation', value: r.week.quotationTotal },
    { key: 'banked', label: 'Banked', value: r.week.bankedTotal },
  ];
  return (
    <figure className="rp-card rp-qb">
      <figcaption className="rp-card-head">
        <div>
          <h2>Quoted and banked</h2>
          <p className="muted">Weekly totals on the same scale</p>
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

const Failed = ({ message }: { message: string }) => (
  <p className="alert error" role="alert">
    {message}
  </p>
);

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
              Weekly target {rand(r.week.weeklyTarget)}: {r.week.targetMet ? 'met' : 'not met'} (
              {Math.round(pct)}%)
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
                      '-'
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

export default function ReportsPage() {
  const session = useSession();
  const manager = hasAnyRole(session, ['ADMIN', 'MANAGER']);
  const [weeks, setWeeks] = useState<WeekListItem[] | null>(null);
  const [scope, setScope] = useState<'ALL' | 'OWN'>('ALL');
  const [week, setWeek] = useState('');
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
        setWeek((w) => w || (r.weeks.at(0)?.weekStarting ?? ''));
      })
      .catch((err) => live && setError(detailOf(err) ?? 'Could not load the weeks.'));
    return () => {
      live = false;
    };
  }, []);

  const summary = useReport<SummaryResponse | null>(
    () => (week ? reports.summary(week) : Promise.resolve(null)),
    [week],
  );

  const tabs: { id: Tab; label: string }[] = [
    { id: 'summary', label: 'Summary' },
    { id: 'consultants', label: 'Consultants' },
    { id: 'products', label: 'Products' },
    ...(manager ? [{ id: 'budget' as Tab, label: 'Budget structure' }] : []),
  ];

  const csv: Record<Tab, string> = {
    summary: 'summary.csv',
    consultants: 'consultants.csv',
    products: `products.csv${consultantId ? `?consultantId=${consultantId}` : ''}`,
    budget: 'budget-structures.csv',
  };

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
        {week && <Download path={`${week}/${csv[tab]}`} />}
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

          {summary.error && tab === 'summary' && <Failed message={summary.error} />}
          {summary.value && tab === 'summary' && (
            <>
              <Kpis r={summary.value} />
              <div className="rp-charts">
                <DailyChart r={summary.value} />
                <QuotedBanked r={summary.value} />
              </div>
            </>
          )}

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

          {week && (
            <section
              className="rp-card rp-panel"
              role="tabpanel"
              aria-label={tabs.find((t) => t.id === tab)?.label}
            >
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
            </section>
          )}
        </>
      )}
    </div>
  );
}
