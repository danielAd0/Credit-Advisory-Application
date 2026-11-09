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
import './addclient.css';
import './consultants.css';
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
    <p className="rp-download">
      <a className="btn" href={`/api/reports/weeks/${path}`}>
        Download as CSV
      </a>
    </p>
  );
}

const Failed = ({ message }: { message: string }) => (
  <p className="alert error" role="alert">
    {message}
  </p>
);

function Summary({ week }: { week: string }) {
  const { value: r, error } = useReport<SummaryResponse>(() => reports.summary(week), [week]);
  if (error) return <Failed message={error} />;
  if (!r) return <p className="muted">Loading...</p>;
  const own = r.scope === 'OWN';
  const pct = r.week.weeklyTarget
    ? Math.min(100, (r.week.quotationTotal / r.week.weeklyTarget) * 100)
    : 0;
  return (
    <>
      <Download path={`${week}/summary.csv`} />
      <div className="rp-stats">
        <div className="wi-stat">
          <strong>{rand(r.week.quotationTotal)}</strong>
          <span>Quotation</span>
        </div>
        <div className="wi-stat">
          <strong>{rand(r.week.bankedTotal)}</strong>
          <span>Banked</span>
        </div>
        <div className="wi-stat">
          <strong>{r.week.transactionCount}</strong>
          <span>Sales</span>
        </div>
        <div className="wi-stat">
          <strong>{r.week.clientCount}</strong>
          <span>Clients</span>
        </div>
      </div>
      {!own && r.week.weeklyTarget !== null && (
        <div className="rp-target">
          <p>
            Weekly target {rand(r.week.weeklyTarget)}:{' '}
            <strong>{r.week.targetMet ? 'met' : 'not met'}</strong> ({Math.round(pct)}%)
          </p>
          <div
            className="rp-bar"
            role="img"
            aria-label={`${Math.round(pct)} percent of the weekly target`}
          >
            <div
              className={`rp-fill ${r.week.targetMet ? 'met' : ''}`}
              style={{ width: `${pct}%` }}
            />
          </div>
          {r.week.unattributedCount ? (
            <p className="muted">
              {r.week.unattributedCount} sale{r.week.unattributedCount === 1 ? ' has' : 's have'} no
              consultant in the register.
            </p>
          ) : null}
        </div>
      )}
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Day</th>
              <th>Date</th>
              <th className="num">Sales</th>
              <th className="num">Clients</th>
              <th className="num">Quotation</th>
              <th className="num">Banked</th>
              {!own && <th>Daily target</th>}
            </tr>
          </thead>
          <tbody>
            {r.days.map((d) => (
              <tr key={d.dayOfWeek}>
                <td>{DAY_NAMES[d.dayOfWeek - 1]}</td>
                <td>{day(d.date)}</td>
                <td className="num">{d.transactionCount}</td>
                <td className="num">{d.clientCount}</td>
                <td className="num">{rand(d.quotationTotal)}</td>
                <td className="num">{rand(d.bankedTotal)}</td>
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
      <p className="muted">
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
      <Download path={`${week}/consultants.csv`} />
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

function Products({ week, manager }: { week: string; manager: boolean }) {
  const [consultantId, setConsultantId] = useState('');
  const [people, setPeople] = useState<ConsultantSummary[]>([]);
  useEffect(() => {
    if (!manager) return;
    let live = true;
    data
      .listConsultants()
      .then((r) => live && setPeople(r))
      .catch(() => undefined);
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
        <div className="ac-field rp-filter">
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
      <Download
        path={`${week}/products.csv${consultantId ? `?consultantId=${consultantId}` : ''}`}
      />
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
      <Download path={`${week}/budget-structures.csv`} />
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

  const tabs: { id: Tab; label: string }[] = [
    { id: 'summary', label: 'Summary' },
    { id: 'consultants', label: 'Consultants' },
    { id: 'products', label: 'Products' },
    ...(manager ? [{ id: 'budget' as Tab, label: 'Budget structures' }] : []),
  ];

  return (
    <div className="rp">
      <h1>Reports</h1>
      <p className="muted">
        {scope === 'OWN'
          ? 'Your own sales, from the imported weekly registers.'
          : 'Company figures from the imported weekly registers.'}
      </p>

      {error && <Failed message={error} />}
      {!error && weeks === null && <p className="muted">Loading...</p>}
      {weeks && weeks.length === 0 && (
        <p className="muted">
          No weekly register has been imported yet. Reports appear after a register is confirmed.
        </p>
      )}
      {weeks && weeks.length > 0 && (
        <>
          <div className="ac-field rp-filter">
            <label htmlFor="rp-week">Week</label>
            <select id="rp-week" value={week} onChange={(e) => setWeek(e.target.value)}>
              {weeks.map((w) => (
                <option key={w.weekStarting} value={w.weekStarting}>
                  Week of {day(w.weekStarting)} ({rand(w.quotationTotal)})
                </option>
              ))}
            </select>
          </div>

          <div className="wi-tabs" role="tablist" aria-label="Report">
            {tabs.map((t) => (
              <button
                key={t.id}
                type="button"
                role="tab"
                className="btn"
                aria-selected={tab === t.id}
                aria-pressed={tab === t.id}
                onClick={() => setTab(t.id)}
              >
                {t.label}
              </button>
            ))}
          </div>

          {week && (
            <section className="wi-card" aria-label={tabs.find((t) => t.id === tab)?.label}>
              {tab === 'summary' && <Summary week={week} />}
              {tab === 'consultants' && <Consultants week={week} />}
              {tab === 'products' && <Products week={week} manager={manager} />}
              {tab === 'budget' && manager && <Budget week={week} />}
            </section>
          )}
        </>
      )}
    </div>
  );
}
