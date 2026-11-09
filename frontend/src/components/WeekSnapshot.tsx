import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { reports, type SummaryResponse } from '../api';
import './weeksnapshot.css';

const DAY_NAMES = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

const rand = (n: number) =>
  n.toLocaleString('en-ZA', { style: 'currency', currency: 'ZAR', maximumFractionDigits: 0 });
const short = (n: number) => (n >= 1000 ? `R${Math.round(n / 1000)}k` : `R${Math.round(n)}`);
const day = (iso: string) =>
  new Date(iso).toLocaleDateString('en-ZA', { day: 'numeric', month: 'short' });

type State =
  | { kind: 'loading' }
  | { kind: 'none' }
  | { kind: 'failed' }
  | { kind: 'ready'; summary: SummaryResponse };


export default function WeekSnapshot() {
  const [state, setState] = useState<State>({ kind: 'loading' });

  useEffect(() => {
    let live = true;
    async function load() {
      try {
        const { weeks } = await reports.weeks();
        const latest = weeks.at(0);
        if (!latest) return live && setState({ kind: 'none' });
        const summary = await reports.summary(latest.weekStarting);
        if (live) setState({ kind: 'ready', summary });
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
  if (state.kind === 'none')
    return (
      <section className="ws" aria-label="Latest week">
        <h2>Latest week</h2>
        <p className="muted">No weekly register has been imported yet.</p>
      </section>
    );
  if (state.kind === 'failed')
    return (
      <section className="ws" aria-label="Latest week">
        <h2>Latest week</h2>
        <p className="muted">The weekly figures could not be loaded.</p>
      </section>
    );

  const r = state.summary;
  const target = r.week.weeklyTarget;
  const pct = target ? Math.min(100, (r.week.quotationTotal / target) * 100) : 0;
  const top = Math.max(1, ...r.days.map((d) => Math.max(d.quotationTotal, d.dailyTarget ?? 0)));

  return (
    <section className="ws" aria-label="Latest week">
      <div className="ws-head">
        <h2>
          {r.scope === 'OWN' ? 'My latest week' : 'Latest week'}: {day(r.weekStarting)} to{' '}
          {day(r.weekEnding)}
        </h2>
        <Link to="/reports">Open the full report</Link>
      </div>

      <p className="ws-totals">
        Quotation <strong>{rand(r.week.quotationTotal)}</strong> · Banked{' '}
        <strong>{rand(r.week.bankedTotal)}</strong> · {r.week.transactionCount} sales
      </p>

      {target !== null && (
        <>
          <p className="ws-target">
            Weekly target {rand(target)}: <strong>{r.week.targetMet ? 'met' : 'not met'}</strong> (
            {Math.round(pct)}%)
          </p>
          <div
            className="ws-bar"
            role="img"
            aria-label={`${Math.round(pct)} percent of the weekly target`}
          >
            <div
              className={`ws-fill ${r.week.targetMet ? 'met' : ''}`}
              style={{ width: `${pct}%` }}
            />
          </div>
        </>
      )}

      <ol className="ws-days" aria-label="Quotation per day">
        {r.days.map((d) => {
          const met = d.targetMet;
          const label = `${DAY_NAMES[d.dayOfWeek - 1]}: ${rand(d.quotationTotal)}${
            met === null ? '' : met ? ', daily target met' : ', daily target not met'
          }`;
          return (
            <li key={d.dayOfWeek} aria-label={label}>
              <span className="ws-amount">{short(d.quotationTotal)}</span>
              <span className="ws-col">
                {d.dailyTarget !== null && (
                  <span className="ws-line" style={{ bottom: `${(d.dailyTarget / top) * 100}%` }} />
                )}
                <span
                  className={`ws-barfill ${met ? 'met' : ''}`}
                  style={{ height: `${(d.quotationTotal / top) * 100}%` }}
                />
              </span>
              <span className="ws-name">
                {DAY_NAMES[d.dayOfWeek - 1]}
                {met ? ' ✓' : ''}
              </span>
            </li>
          );
        })}
      </ol>
      {r.scope === 'ALL' && (
        <p className="muted ws-key">
          The dashed line is the daily target. Green bars with a tick met it.
        </p>
      )}
    </section>
  );
}
