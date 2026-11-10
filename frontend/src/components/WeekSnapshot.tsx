import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import type { SummaryResponse } from '../api';
import { IconArrowUpRight, IconCheck } from './icons';
import './weeksnapshot.css';

const DAY_NAMES = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

const rand = (n: number) =>
  n.toLocaleString('en-ZA', { style: 'currency', currency: 'ZAR', maximumFractionDigits: 0 });
const short = (n: number) => (n >= 1000 ? `R${Math.round(n / 1000)}k` : `R${Math.round(n)}`);
const day = (iso: string) =>
  new Date(iso).toLocaleDateString('en-ZA', { day: 'numeric', month: 'short' });

export type WeekState =
  | { kind: 'loading' }
  | { kind: 'none' }
  | { kind: 'failed' }
  | { kind: 'ready'; summary: SummaryResponse };

function Shell({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="ws dash-card" aria-label="Latest week">
      <div className="ws-head">
        <div>
          <h2>{title}</h2>
        </div>
      </div>
      {children}
    </section>
  );
}

export default function WeekSnapshot({ state }: { state: WeekState }) {
  if (state.kind === 'loading')
    return (
      <Shell title="Latest week">
        <p className="muted">Loading...</p>
      </Shell>
    );
  if (state.kind === 'none')
    return (
      <Shell title="Latest week">
        <p className="muted">No weekly register has been imported yet.</p>
      </Shell>
    );
  if (state.kind === 'failed')
    return (
      <Shell title="Latest week">
        <p className="muted">The weekly figures could not be loaded.</p>
      </Shell>
    );

  const r = state.summary;
  const target = r.week.weeklyTarget;
  const pct = target ? Math.min(100, (r.week.quotationTotal / target) * 100) : 0;
  const top = Math.max(1, ...r.days.map((d) => Math.max(d.quotationTotal, d.dailyTarget ?? 0)));
  const anyDailyTarget = r.days.some((d) => d.dailyTarget !== null);

  return (
    <section className="ws dash-card" aria-label="Latest week">
      <div className="ws-head">
        <div>
          <h2>{r.scope === 'OWN' ? 'My latest week' : 'Latest week'}</h2>
          <p className="muted">
            {day(r.weekStarting)} to {day(r.weekEnding)}
          </p>
        </div>
        <Link className="text-link" to="/reports">
          Open the full report <IconArrowUpRight />
        </Link>
      </div>

      {target !== null && (
        <div className="ws-target">
          <div className="ws-target-row">
            <div>
              <p className="ws-target-label">Weekly target</p>
              <p className="ws-target-value">{rand(target)}</p>
            </div>
            <span className={`pill ${r.week.targetMet ? 'good' : 'warn'}`}>
              {r.week.targetMet && <IconCheck />}
              {r.week.targetMet ? 'Target met' : 'Target not met'} · {Math.round(pct)}%
            </span>
          </div>
          <div
            className="ws-bar"
            role="img"
            aria-label={`${Math.round(pct)} percent of the weekly target`}
          >
            <div className="ws-fill" style={{ width: `${pct}%` }} />
          </div>
        </div>
      )}

      <p className="eyebrow muted ws-daily-label">Daily performance</p>
      <ol className="ws-days" aria-label="Quotation per day">
        {r.days.map((d) => {
          const met = d.targetMet;
          const name = DAY_NAMES[d.dayOfWeek - 1];
          const label = `${name}: ${rand(d.quotationTotal)}${
            met === null ? '' : met ? ', daily target met' : ', daily target not met'
          }`;
          return (
            <li key={d.dayOfWeek} aria-label={label}>
              <span className="ws-amount">{short(d.quotationTotal)}</span>
              <span className="ws-col">
                <span
                  className={`ws-barfill ${met === false ? 'missed' : ''}`}
                  style={{ height: `${(d.quotationTotal / top) * 100}%` }}
                />
                {d.dailyTarget !== null && (
                  <span className="ws-line" style={{ bottom: `${(d.dailyTarget / top) * 100}%` }} />
                )}
              </span>
              <span className={`ws-name ${met ? 'met' : ''}`}>
                {name}
                {met && <IconCheck />}
              </span>
            </li>
          );
        })}
      </ol>
      {anyDailyTarget && (
        <p className="muted ws-key">
          The dashed line is the daily target.
          <br />
          Orange bars with a tick met it.
        </p>
      )}
    </section>
  );
}
