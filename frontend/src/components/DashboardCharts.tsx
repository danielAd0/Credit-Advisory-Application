import { useEffect, useState, type ReactNode } from 'react';
import {
  reports,
  type ConsultantsResponse,
  type ProductsResponse,
  type SummaryResponse,
  type WeekListItem,
} from '../api';
import './dashboardcharts.css';

const rand = (n: number) =>
  n.toLocaleString('en-ZA', { style: 'currency', currency: 'ZAR', maximumFractionDigits: 0 });
const compact = (n: number) =>
  n >= 1_000_000
    ? `R${+(n / 1_000_000).toFixed(1)}m`
    : n >= 1000
      ? `R${Math.round(n / 1000)}k`
      : `R${n}`;
const dayLabel = (iso: string) =>
  new Date(iso).toLocaleDateString('en-ZA', { day: 'numeric', month: 'short' });

interface Bar {
  key: string;
  label: string;
  value: number;
  value2?: number;
}

type Fmt = (n: number) => string;
const count: Fmt = (n) => n.toLocaleString('en-ZA');
const DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

function niceMax(v: number): number {
  if (v <= 0) return 1;
  const p = 10 ** Math.floor(Math.log10(v));
  const n = v / p;
  return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10) * p;
}

function ChartCard({
  title,
  note,
  head,
  rows,
  fmt = rand,
  children,
}: {
  title: string;
  note: string;
  head: string[];
  rows: Bar[];
  fmt?: Fmt;
  children: ReactNode;
}) {
  const [table, setTable] = useState(false);
  return (
    <figure className="dc-card">
      <figcaption>
        <span>
          <strong>{title}</strong>
          <span className="muted dc-note">{note}</span>
        </span>
        <button type="button" className="dc-toggle" onClick={() => setTable((t) => !t)}>
          {table ? 'Show chart' : 'Show table'}
        </button>
      </figcaption>
      {table ? (
        <table className="dc-table">
          <thead>
            <tr>
              <th>{head[0]}</th>
              {head.slice(1).map((h) => (
                <th key={h} className="num">
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.key}>
                <td>{r.label}</td>
                <td className="num">{fmt(r.value)}</td>
                {r.value2 !== undefined && <td className="num">{fmt(r.value2)}</td>}
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        children
      )}
    </figure>
  );
}

function Columns({
  bars,
  target,
  fmt = rand,
  axis = compact,
  hot = bars.length - 1,
}: {
  bars: Bar[];
  target: number | null;
  fmt?: Fmt;
  axis?: Fmt;
  hot?: number;
}) {
  const top = niceMax(Math.max(...bars.map((b) => b.value), target ?? 0));
  const ticks = [0, 0.5, 1].map((f) => top * f);
  return (
    <div className="dc-cols">
      <div className="dc-yaxis" aria-hidden="true">
        {[...ticks].reverse().map((t) => (
          <span key={t}>{axis(t)}</span>
        ))}
      </div>
      <div className="dc-plot">
        {ticks.map((t) => (
          <span key={t} className="dc-grid" style={{ bottom: `${(t / top) * 100}%` }} />
        ))}
        {target !== null && (
          <span className="dc-target" style={{ bottom: `${(target / top) * 100}%` }}>
            <span>Weekly target {compact(target)}</span>
          </span>
        )}
        <ol>
          {bars.map((b, i) => (
            <li key={b.key}>
              <span className="dc-colwrap">
                <span
                  className={`dc-col ${i === hot ? 'now' : ''}`}
                  style={{ height: `${(b.value / top) * 100}%` }}
                  tabIndex={0}
                  aria-label={`${b.label}: ${fmt(b.value)}`}
                >
                  <span className="dc-tip" role="presentation">
                    <strong>{fmt(b.value)}</strong>
                    {b.label}
                  </span>
                </span>
              </span>
              <span className="dc-x">{b.label}</span>
            </li>
          ))}
        </ol>
      </div>
    </div>
  );
}

function Ranked({ bars, fmt = rand, axis = compact }: { bars: Bar[]; fmt?: Fmt; axis?: Fmt }) {
  const top = Math.max(1, ...bars.map((b) => b.value));
  return (
    <ol className="dc-rank">
      {bars.map((b) => (
        <li key={b.key}>
          <span className="dc-name" title={b.label}>
            {b.label}
          </span>
          <span className="dc-track">
            <span
              className="dc-hbar"
              style={{ width: `${(b.value / top) * 100}%` }}
              tabIndex={0}
              aria-label={`${b.label}: ${fmt(b.value)}`}
            >
              <span className="dc-tip" role="presentation">
                <strong>{fmt(b.value)}</strong>
                {b.label}
              </span>
            </span>
          </span>
          <span className="dc-val">{axis(b.value)}</span>
        </li>
      ))}
    </ol>
  );
}

function Paired({ bars }: { bars: Bar[] }) {
  const top = niceMax(Math.max(1, ...bars.flatMap((b) => [b.value, b.value2 ?? 0])));
  const ticks = [0, 0.5, 1].map((f) => top * f);
  return (
    <>
      <p className="dc-legend">
        <span>
          <i className="dc-key" /> Quotation
        </span>
        <span>
          <i className="dc-key two" /> Banked
        </span>
      </p>
      <div className="dc-cols">
        <div className="dc-yaxis" aria-hidden="true">
          {[...ticks].reverse().map((t) => (
            <span key={t}>{compact(t)}</span>
          ))}
        </div>
        <div className="dc-plot">
          {ticks.map((t) => (
            <span key={t} className="dc-grid" style={{ bottom: `${(t / top) * 100}%` }} />
          ))}
          <ol>
            {bars.map((b) => (
              <li key={b.key}>
                <span className="dc-colwrap pair">
                  {[b.value, b.value2 ?? 0].map((v, n) => (
                    <span
                      key={n}
                      className={`dc-col now ${n ? 'two' : ''}`}
                      style={{ height: `${(v / top) * 100}%` }}
                      tabIndex={0}
                      aria-label={`${b.label} ${n ? 'banked' : 'quotation'}: ${rand(v)}`}
                    >
                      <span className="dc-tip" role="presentation">
                        <strong>{rand(v)}</strong>
                        {b.label} {n ? 'banked' : 'quotation'}
                      </span>
                    </span>
                  ))}
                </span>
                <span className="dc-x">{b.label}</span>
              </li>
            ))}
          </ol>
        </div>
      </div>
    </>
  );
}

type State =
  | { kind: 'loading' }
  | { kind: 'none' }
  | { kind: 'failed' }
  | {
      kind: 'ready';
      weeks: WeekListItem[];
      target: number | null;
      summary: SummaryResponse;
      consultants: ConsultantsResponse;
      products: ProductsResponse;
    };

export default function DashboardCharts() {
  const [state, setState] = useState<State>({ kind: 'loading' });

  useEffect(() => {
    let live = true;
    async function load() {
      try {
        const { weeks } = await reports.weeks();
        const latest = weeks.at(0);
        if (!latest) return live && setState({ kind: 'none' });
        const [summary, consultants, products] = await Promise.all([
          reports.summary(latest.weekStarting),
          reports.consultants(latest.weekStarting),
          reports.products(latest.weekStarting),
        ]);
        if (live)
          setState({
            kind: 'ready',
            weeks,
            target: summary.week.weeklyTarget,
            summary,
            consultants,
            products,
          });
      } catch {
        if (live) setState({ kind: 'failed' });
      }
    }
    void load();
    return () => {
      live = false;
    };
  }, []);

  if (state.kind === 'loading') return null;
  if (state.kind !== 'ready')
    return (
      <section className="dc" aria-label="Charts">
        <h2>Charts</h2>
        <p className="muted">
          {state.kind === 'none'
            ? 'Charts appear once a weekly register has been imported.'
            : 'The charts could not be loaded.'}
        </p>
      </section>
    );

  const weekBars: Bar[] = state.weeks
    .slice(0, 8)
    .reverse()
    .map((w) => ({
      key: w.weekStarting,
      label: dayLabel(w.weekStarting),
      value: w.quotationTotal,
    }));

  const staffBars: Bar[] = state.consultants.consultants
    .map((c) => ({
      key: c.consultant ? String(c.consultant.id) : 'none',
      label: c.consultant ? c.consultant.fullName : 'No consultant',
      value: c.week.quotationTotal,
    }))
    .sort((a, b) => b.value - a.value);

  const productBars: Bar[] = state.products.products
    .map((p) => ({ key: String(p.product.id), label: p.product.name, value: p.week.totalAmount }))
    .sort((a, b) => b.value - a.value)
    .slice(0, 8);

  const dayBars: Bar[] = state.summary.days.map((d) => ({
    key: String(d.dayOfWeek),
    label: DAYS.at(d.dayOfWeek - 1) ?? '',
    value: d.quotationTotal,
    value2: d.bankedTotal,
  }));
  const clientDays: Bar[] = state.summary.days.map((d) => ({
    key: String(d.dayOfWeek),
    label: DAYS.at(d.dayOfWeek - 1) ?? '',
    value: d.clientCount,
  }));
  const busiest = clientDays.reduce(
    (m, b, i) => (b.value > (clientDays.at(m)?.value ?? 0) ? i : m),
    0,
  );
  const clientBars: Bar[] = state.consultants.consultants
    .map((c) => ({
      key: c.consultant ? String(c.consultant.id) : 'none',
      label: c.consultant ? c.consultant.fullName : 'No consultant',
      value: c.week.clientCount,
    }))
    .sort((a, b) => b.value - a.value);

  const week = dayLabel(state.consultants.weekStarting);

  return (
    <section className="dc" aria-label="Charts">
      <h2 className="sr-only">Charts</h2>
      <div className="dc-grid-cards">
        <ChartCard
          title="Quotation per week"
          note="Most recent weeks, newest on the right"
          head={['Week starting', 'Quotation']}
          rows={weekBars}
        >
          <Columns bars={weekBars} target={state.target} />
        </ChartCard>
        <ChartCard
          title="Quotation and banked per day"
          note={`Week starting ${week}`}
          head={['Day', 'Quotation', 'Banked']}
          rows={dayBars}
        >
          <Paired bars={dayBars} />
        </ChartCard>
        <ChartCard
          title="Clients per day"
          note={`Busiest day highlighted, week starting ${week}`}
          head={['Day', 'Clients']}
          rows={clientDays}
          fmt={count}
        >
          <Columns bars={clientDays} target={null} fmt={count} axis={count} hot={busiest} />
        </ChartCard>
        <ChartCard
          title="Quotation per consultant"
          note={`Week starting ${week}`}
          head={['Consultant', 'Quotation']}
          rows={staffBars}
        >
          <Ranked bars={staffBars} />
        </ChartCard>
        <ChartCard
          title="Clients per consultant"
          note={`Week starting ${week}`}
          head={['Consultant', 'Clients']}
          rows={clientBars}
          fmt={count}
        >
          <Ranked bars={clientBars} fmt={count} axis={count} />
        </ChartCard>
        <ChartCard
          title="Top products"
          note={`Week starting ${week}, by amount`}
          head={['Product', 'Amount']}
          rows={productBars}
        >
          <Ranked bars={productBars} />
        </ChartCard>
      </div>
    </section>
  );
}
