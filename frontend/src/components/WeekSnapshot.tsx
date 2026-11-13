import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import type { SummaryResponse, Target, WeekListItem } from '../api';
import { IconArrowUpRight, IconCheck } from './icons';
import './weeksnapshot.css';

const DAY_NAMES = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

const rand = (n: number) =>
  n.toLocaleString('en-ZA', { style: 'currency', currency: 'ZAR', maximumFractionDigits: 0 });
const short = (n: number) => (n >= 1000 ? `R${Math.round(n / 1000)}k` : `R${Math.round(n)}`);
const day = (iso: string) =>
  new Date(iso).toLocaleDateString('en-ZA', { day: 'numeric', month: 'short' });
const fullDay = (iso: string) =>
  new Date(iso).toLocaleDateString('en-ZA', { day: 'numeric', month: 'short', year: 'numeric' });

export type WeekState =
  | { kind: 'loading' }
  | { kind: 'none' }
  | { kind: 'failed' }
  | { kind: 'ready'; summary: SummaryResponse };

/** The weeks that can be picked, and which one is shown. */
export interface WeekChoice {
  weeks: WeekListItem[];
  selected: string;
  onSelect: (weekStarting: string) => void;
}

/**
 * The target in force today (managers only). Shown next to the week's own
 * target, because an older week keeps the target it had at the time.
 */
export type CurrentTarget =
  | { kind: 'hidden' }
  | { kind: 'ready'; target: Target | null; today: string };

function Shell({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="ws dash-card" aria-label="Weekly performance">
      <div className="ws-head">
        <div>
          <h2>{title}</h2>
        </div>
      </div>
      {children}
    </section>
  );
}

/** Monday of the week that contains the given yyyy-mm-dd date. */
const mondayOf = (iso: string) => {
  const d = new Date(iso + 'T00:00:00Z');
  d.setUTCDate(d.getUTCDate() - ((d.getUTCDay() + 6) % 7));
  return d.toISOString().slice(0, 10);
};

function TodaysTarget({
  current,
  weekTarget,
}: {
  current: CurrentTarget;
  weekTarget: number | null;
}) {
  if (current.kind !== 'ready') return null;
  const t = current.target;
  if (!t || t.weeklyTarget === null)
    return (
      <div className="ws-now">
        <p>
          <span className="ws-now-label">Today’s target</span> <strong>Not configured</strong>
        </p>
        <Link className="text-link" to="/targets">
          Set targets <IconArrowUpRight />
        </Link>
      </div>
    );
  const same = t.weeklyTarget === weekTarget;
  return (
    <div className={`ws-now ${same ? '' : 'changed'}`}>
      <p>
        <span className="ws-now-label">Today’s target</span> <strong>{rand(t.weeklyTarget)}</strong>{' '}
        a week ({rand(t.dailyTarget)} a day), in force since {fullDay(t.effectiveFrom)}.
      </p>
      {!same && (
        <p className="muted">
          {weekTarget === null
            ? 'This target started after this week, so it does not apply to it.'
            : 'The target has changed since this week. This week is still measured against the target it had at the time.'}
        </p>
      )}
      <Link className="text-link" to="/targets">
        Targets <IconArrowUpRight />
      </Link>
    </div>
  );
}

export default function WeekSnapshot({
  state,
  choice,
  current = { kind: 'hidden' },
}: {
  state: WeekState;
  choice?: WeekChoice;
  current?: CurrentTarget;
}) {
  if (state.kind === 'loading')
    return (
      <Shell title="Weekly performance">
        <p className="muted">Loading...</p>
      </Shell>
    );
  if (state.kind === 'none')
    return (
      <Shell title="Weekly performance">
        <p className="muted">No weekly register has been imported yet.</p>
      </Shell>
    );
  if (state.kind === 'failed')
    return (
      <Shell title="Weekly performance">
        <p className="muted">The weekly figures could not be loaded.</p>
      </Shell>
    );

  const r = state.summary;
  const target = r.week.weeklyTarget;
  const pct = target ? Math.min(100, (r.week.quotationTotal / target) * 100) : 0;
  const top = Math.max(1, ...r.days.map((d) => Math.max(d.quotationTotal, d.dailyTarget ?? 0)));
  const anyDailyTarget = r.days.some((d) => d.dailyTarget !== null);
  const latest = choice ? choice.weeks.at(0)?.weekStarting === r.weekStarting : false;
  const today = current.kind === 'ready' ? current.today : new Date().toISOString().slice(0, 10);
  const thisMonday = mondayOf(today);
  const behind = latest && r.weekStarting < thisMonday;

  return (
    <section className="ws dash-card" aria-label="Weekly performance">
      <div className="ws-head">
        <div>
          <h2>
            {r.scope === 'OWN' ? 'My week of ' : 'Week of '}
            {fullDay(r.weekStarting)}
            {latest && <span className="pill ws-latest">Latest import</span>}
          </h2>
          <p className="muted">
            Monday {day(r.weekStarting)} to Sunday {day(r.weekEnding)}
          </p>
        </div>
        <Link className="text-link" to={`/reports?week=${r.weekStarting}`}>
          Open the full report <IconArrowUpRight />
        </Link>
      </div>

      {choice && choice.weeks.length > 1 && (
        <div className="ws-pick">
          <label htmlFor="ws-week">Show week</label>
          <select
            id="ws-week"
            value={choice.selected}
            onChange={(e) => choice.onSelect(e.target.value)}
          >
            {choice.weeks.map((w, i) => (
              <option key={w.weekStarting} value={w.weekStarting}>
                Week of {fullDay(w.weekStarting)}
                {i === 0 ? ' (latest)' : ''}
              </option>
            ))}
          </select>
          <Link className="text-link" to="/reports?period=month">
            Month or date range <IconArrowUpRight />
          </Link>
        </div>
      )}

      {behind && (
        <p className="ws-behind">
          No register has been imported for the week of {fullDay(thisMonday)} yet, so these are the
          most recent figures.
        </p>
      )}

      {target === null && r.scope === 'ALL' && (
        <div className="ws-target">
          <p className="ws-target-label">Target for that week</p>
          <p className="ws-target-value ws-unset">Target not configured</p>
          <p className="muted ws-target-sub">
            Quoted {rand(r.week.quotationTotal)}. No weekly target was set for this week, so there
            is nothing to measure it against.
          </p>
          <TodaysTarget current={current} weekTarget={null} />
        </div>
      )}

      {target !== null && (
        <div className="ws-target">
          <div className="ws-target-row">
            <div>
              <p className="ws-target-label">Target for that week</p>
              <p className="ws-target-value">{rand(target)}</p>
              <p className="muted ws-target-sub">
                Quoted {rand(r.week.quotationTotal)} of {rand(target)}
              </p>
            </div>
            <span className={`pill ${r.week.targetMet ? 'good' : 'warn'}`}>
              {r.week.targetMet && <IconCheck />}
              {r.week.targetMet ? 'Target met' : 'Target not met'} · {Math.round(pct)}%
            </span>
          </div>
          <div
            className="ws-bar"
            role="img"
            aria-label={`${Math.round(pct)} percent of the target for that week`}
          >
            <div className="ws-fill" style={{ width: `${pct}%` }} />
          </div>
          <TodaysTarget current={current} weekTarget={target} />
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
      {!anyDailyTarget && r.scope === 'ALL' && (
        <p className="muted ws-key">No daily target configured for these days.</p>
      )}
      {anyDailyTarget && (
        <p className="muted ws-key">
          The dashed line is that day’s target.
          <br />
          Orange bars with a tick met it.
        </p>
      )}
    </section>
  );
}
