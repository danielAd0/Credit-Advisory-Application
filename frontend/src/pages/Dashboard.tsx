import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { data, reports, targets, type DashboardSummary, type WeekListItem } from '../api';
import { hasAnyRole, useSession } from '../auth/useAuth';
import { IconArrowRight, IconArrowUpRight } from '../components/icons';
import WeekSnapshot, { type CurrentTarget, type WeekState } from '../components/WeekSnapshot';
import './dashboard.css';

const rand = (n: number) =>
  n.toLocaleString('en-ZA', { style: 'currency', currency: 'ZAR', maximumFractionDigits: 0 });
const count = (n: number) => n.toLocaleString('en-ZA');
const shortDay = (iso: string) =>
  new Date(iso).toLocaleDateString('en-ZA', { day: 'numeric', month: 'short' });

type Tone = 'attention' | 'warning';

interface Row {
  to?: string;
  label: string;
  value: number;
  note?: string;
  tone?: Tone;
}

function Kpi({ label, value, note }: { label: string; value: string; note: string }) {
  return (
    <div className="kpi">
      <p className="kpi-label">{label}</p>
      <p className="kpi-value">{value}</p>
      <p className="kpi-note">{note}</p>
    </div>
  );
}

function StatusTable({ rows, own }: { rows: Row[]; own: boolean }) {
  return (
    <section className="st dash-card" aria-label="Status summary">
      <h2>Status summary</h2>
      <p className="muted st-sub">
        {own
          ? 'Your clients and cases at a glance.'
          : 'Clients, consultants, and cases at a glance.'}
      </p>
      <table>
        <colgroup>
          <col className="c-metric" />
          <col className="c-count" />
          <col />
        </colgroup>
        <thead>
          <tr>
            <th>Metric</th>
            <th>Count</th>
            <th>Details</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.label} className={r.tone ?? ''}>
              <td>{r.to ? <Link to={r.to}>{r.label}</Link> : r.label}</td>
              <td className="st-count">{count(r.value)}</td>
              <td className="muted st-note">{r.note}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

export default function Dashboard() {
  const session = useSession();
  const manager = hasAnyRole(session, ['ADMIN', 'MANAGER']);
  const [summary, setSummary] = useState<DashboardSummary | null>(null);
  const [failed, setFailed] = useState(false);
  const [weeks, setWeeks] = useState<WeekListItem[]>([]);
  const [selected, setSelected] = useState('');
  const [week, setWeek] = useState<WeekState>({ kind: 'loading' });
  const [current, setCurrent] = useState<CurrentTarget>({ kind: 'hidden' });

  useEffect(() => {
    let live = true;
    data
      .getDashboardSummary()
      .then((s) => live && setSummary(s))
      .catch(() => live && setFailed(true));
    reports
      .weeks()
      .then((r) => {
        if (!live) return;
        setWeeks(r.weeks);
        const latest = r.weeks.at(0);
        if (latest) setSelected(latest.weekStarting);
        else setWeek({ kind: 'none' });
      })
      .catch(() => live && setWeek({ kind: 'failed' }));
    return () => {
      live = false;
    };
  }, []);

  // Today's target sits next to the week's own one, so an old week's target
  // isn't mistaken for the one in force now. Targets are for managers only.
  useEffect(() => {
    if (!manager) return;
    let live = true;
    targets
      .list()
      .then((r) => live && setCurrent({ kind: 'ready', target: r.current, today: r.today }))
      .catch(() => undefined); // optional extra
    return () => {
      live = false;
    };
  }, [manager]);

  useEffect(() => {
    if (!selected) return;
    let live = true;
    setWeek({ kind: 'loading' });
    reports
      .summary(selected)
      .then((s) => live && setWeek({ kind: 'ready', summary: s }))
      .catch(() => live && setWeek({ kind: 'failed' }));
    return () => {
      live = false;
    };
  }, [selected]);

  const own = summary?.scope === 'OWN';

  const rows: Row[] = summary
    ? [
        { to: '/clients', label: own ? 'My clients' : 'Clients', value: summary.totalClients },
        ...(summary.unassignedClients !== null
          ? [
              {
                to: '/reassign',
                label: 'Waiting for a consultant',
                value: summary.unassignedClients,
                note: summary.unassignedClients > 0 ? 'Action needed' : 'None waiting',
                tone: 'attention' as const,
              },
            ]
          : []),
        ...(summary.clientsNeedingHandover !== null
          ? [
              {
                to: '/reassign',
                label: 'Need a handover',
                value: summary.clientsNeedingHandover,
                note: 'With an inactive or suspended consultant',
                tone: 'warning' as const,
              },
            ]
          : []),
        ...(summary.consultants
          ? [
              {
                to: '/consultants',
                label: 'Active consultants',
                value: summary.consultants.active,
                note: `${summary.consultants.inactive} inactive, ${summary.consultants.suspended} suspended`,
              },
            ]
          : []),
        { label: 'Open cases', value: summary.cases.open },
        { label: 'Cases in progress', value: summary.cases.inProgress },
        { label: 'Closed cases', value: summary.cases.closed },
      ]
    : [];

  const w = week.kind === 'ready' ? week.summary.week : null;
  const noWeek = week.kind === 'loading' ? '…' : '—';
  const weekName =
    week.kind === 'ready' ? `week of ${shortDay(week.summary.weekStarting)}` : 'week';

  return (
    <div className="dash">
      <header className="dash-head">
        <div>
          <p className="eyebrow">{own ? 'My performance' : 'Performance overview'}</p>
          <h1>Credit Advisory Performance</h1>
          <p className="muted">
            {own
              ? 'Your weekly sales, your clients, and the work that needs your attention.'
              : 'Weekly sales, client status, and the work that needs your attention.'}
          </p>
        </div>
        <Link className="btn primary btn-lg" to="/reports">
          View reports <IconArrowUpRight />
        </Link>
      </header>

      {failed && (
        <p className="alert error" role="alert">
          Could not load the dashboard. Refresh and try again.
        </p>
      )}
      {!failed && summary === null && <p className="muted">Loading...</p>}

      {summary && (
        <>
          <div className="kpis">
            <Kpi
              label="Quotation"
              value={w ? rand(w.quotationTotal) : noWeek}
              note={`Quoted, ${weekName}`}
            />
            <Kpi
              label="Banked"
              value={w ? rand(w.bankedTotal) : noWeek}
              note={`Banked, ${weekName}`}
            />
            <Kpi
              label="Sales"
              value={w ? count(w.transactionCount) : noWeek}
              note={`Sales, ${weekName}`}
            />
            <Kpi
              label="Clients"
              value={count(summary.totalClients)}
              note={own ? 'My clients' : 'Total clients'}
            />
          </div>

          <div className="dash-top">
            <WeekSnapshot
              state={week}
              choice={{ weeks, selected, onSelect: setSelected }}
              current={current}
            />
            <StatusTable rows={rows} own={own} />
          </div>

          {manager && (
            <section className="dash-cta" aria-label="Charts">
              <div>
                <h2>Charts</h2>
                <p className="muted">Review performance charts.</p>
              </div>
              <Link className="btn primary" to="/charts">
                Review charts <IconArrowRight />
              </Link>
            </section>
          )}
        </>
      )}
    </div>
  );
}
